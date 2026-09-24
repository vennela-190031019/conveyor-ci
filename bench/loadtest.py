#!/usr/bin/env python3
"""
Conveyor CI load test.

Starts an isolated Conveyor cluster on this machine (1 API/scheduler process + N worker
processes) against a separate `conveyor_bench` database, drives it with synthetic pipelines,
and reads every timing from Postgres, the engine's own source of truth.

Jobs use the *simulated* runtime (conveyor.runtime=simulated): `sleep 1` really waits a
second, but no container is started. That measures the engine (scheduling, Redis dispatch,
atomic claims, heartbeats, log streaming) rather than Docker's per-container start-up cost.

Scenarios:
  1. DAG latency: 5-job chains, with interval-only scheduling vs. event wake-ups
  2. Throughput: 256 one-second jobs on 1, 2 and 4 workers (8 slots each)
  3. Burst: 500 runs submitted by 16 concurrent clients
  4. Crash recovery: kill -9 a worker mid-run; every run must still succeed

Usage (from the repo root, with Docker running):
    python3 bench/loadtest.py            # builds the jar first
    python3 bench/loadtest.py --no-build
    python3 bench/loadtest.py --quick    # smaller, ~1 minute smoke run

Writes bench/results.md and bench/results.json, and copies the tables into README.md
(between the bench markers). Process logs go to bench/logs/.
Only the Python 3 standard library is needed.
"""

import argparse
import concurrent.futures
import glob
import json
import os
import platform
import shlex
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BENCH = ROOT / "bench"
LOGS = BENCH / "logs"

API_PORT = int(os.environ.get("BENCH_API_PORT", "8090"))
API = f"http://localhost:{API_PORT}"
DB_NAME = "conveyor_bench"
DB_HOST_PORT = os.environ.get("BENCH_DB", "localhost:54329")
REDIS_HOST = os.environ.get("BENCH_REDIS_HOST", "localhost")
REDIS_PORT = os.environ.get("BENCH_REDIS_PORT", "63790")
REDIS_DB = "1"  # keeps the benchmark's queues apart from a dev instance using db 0

# psql / redis-cli run inside the docker compose containers unless overridden.
PSQL = shlex.split(os.environ.get(
    "BENCH_PSQL", "docker compose exec -T postgres psql -U conveyor"))
REDIS_CLI = shlex.split(os.environ.get(
    "BENCH_REDIS_CLI", "docker compose exec -T redis redis-cli"))

SLOTS_PER_WORKER = 8
LEASE_SECONDS = 10
TERMINAL = ("SUCCEEDED", "FAILED", "CANCELLED")


# --------------------------------------------------------------------------- helpers

def log(msg=""):
    print(msg, flush=True)


def run(cmd, **kw):
    return subprocess.run(cmd, cwd=ROOT, check=True, text=True, capture_output=True, **kw).stdout


def sql(query, db=DB_NAME):
    """Runs a query and returns rows as lists of strings."""
    out = run(PSQL + ["-d", db, "-v", "ON_ERROR_STOP=1", "-At", "-F", "\t", "-c", query])
    return [line.split("\t") for line in out.splitlines() if line]


def sql_value(query):
    rows = sql(query)
    return rows[0][0] if rows else None


def http(method, path, body=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def percentile(values, p):
    """Nearest-rank percentile."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, -(-len(ordered) * p // 100))  # ceil
    return ordered[int(rank) - 1]


def summarize_ms(values):
    return {
        "n": len(values),
        "p50": round(percentile(values, 50), 1) if values else None,
        "p95": round(percentile(values, 95), 1) if values else None,
        "p99": round(percentile(values, 99), 1) if values else None,
        "max": round(max(values), 1) if values else None,
    }


def fmt_ms(v):
    if v is None:
        return "-"
    return f"{v / 1000:.2f} s" if v >= 999.5 else f"{v:.0f} ms"


# --------------------------------------------------------------------------- pipelines

def chain_pipeline(length):
    lines = ["name: chain", "jobs:"]
    for i in range(1, length + 1):
        needs = f", needs: j{i - 1}" if i > 1 else ""
        lines.append(f"  j{i}: {{ image: alpine{needs}, steps: [ {{ run: echo step-{i} }} ] }}")
    return "\n".join(lines) + "\n"


def fanout_pipeline(jobs, seconds, retries=0):
    lines = ["name: fanout", "jobs:"]
    for i in range(1, jobs + 1):
        lines.append(f"  t{i:02d}: {{ image: alpine, retries: {retries}, "
                     f"steps: [ {{ run: sleep {seconds} }} ] }}")
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------- cluster

class Cluster:
    """The API/scheduler process and worker processes under test."""

    def __init__(self, jar):
        self.jar = jar
        self.api = None
        self.workers = {}
        self.wake = None

    def common_args(self, wake):
        return [
            f"--spring.datasource.url=jdbc:postgresql://{DB_HOST_PORT}/{DB_NAME}",
            f"--spring.data.redis.host={REDIS_HOST}",
            f"--spring.data.redis.port={REDIS_PORT}",
            f"--spring.data.redis.database={REDIS_DB}",
            "--conveyor.runtime=simulated",
            f"--conveyor.worker.lease-seconds={LEASE_SECONDS}",
            f"--conveyor.scheduler.wake-on-events={'true' if wake else 'false'}",
            "--conveyor.scheduler.redispatch-seconds=10",
            "--logging.level.root=WARN",
            "--spring.main.banner-mode=off",
        ]

    def _spawn(self, name, jvm, args):
        LOGS.mkdir(parents=True, exist_ok=True)
        out = open(LOGS / f"{name}.log", "w")
        return subprocess.Popen(["java", *jvm, "-jar", str(self.jar), *args], cwd=ROOT,
                                stdout=out, stderr=subprocess.STDOUT, start_new_session=True)

    def start_api(self, wake):
        self.wake = wake
        self.api = self._spawn("api", ["-Xmx768m"], [
            f"--server.port={API_PORT}",
            "--conveyor.worker.enabled=false",
            *self.common_args(wake),
        ])
        deadline = time.time() + 120
        while time.time() < deadline:
            if self.api.poll() is not None:
                raise SystemExit(f"API process exited early; see {LOGS / 'api.log'}")
            try:
                if http("GET", "/actuator/health", timeout=2).get("status") == "UP":
                    return
            except (urllib.error.URLError, ConnectionError, OSError, ValueError):
                pass
            time.sleep(0.5)
        raise SystemExit(f"API did not become healthy in 120s; see {LOGS / 'api.log'}")

    def start_workers(self, names):
        for name in names:
            self.workers[name] = self._spawn(name, ["-Xmx384m"], [
                "--spring.main.web-application-type=none",
                "--conveyor.scheduler.enabled=false",
                f"--conveyor.worker.id={name}",
                f"--conveyor.worker.concurrency={SLOTS_PER_WORKER}",
                *self.common_args(self.wake),
            ])
        deadline = time.time() + 120
        while time.time() < deadline:
            for name in names:
                if self.workers[name].poll() is not None:
                    raise SystemExit(f"worker {name} exited early; see {LOGS / (name + '.log')}")
            alive = {w["id"] for w in http("GET", "/api/workers") if w.get("alive")}
            if all(n in alive for n in names):
                return
            time.sleep(0.5)
        raise SystemExit("workers did not register in 120s; see bench/logs/")

    def stop_worker(self, name, hard=False):
        proc = self.workers.pop(name)
        if hard:
            proc.kill()  # SIGKILL: no shutdown hooks, no goodbye, leases just stop being renewed
        else:
            proc.terminate()
        proc.wait(timeout=30)

    def slots(self):
        return len(self.workers) * SLOTS_PER_WORKER

    def stop_all(self):
        for name in list(self.workers):
            try:
                self.stop_worker(name)
            except Exception:
                self.workers.pop(name, None)
        if self.api and self.api.poll() is None:
            self.api.terminate()
            try:
                self.api.wait(timeout=30)
            except subprocess.TimeoutExpired:
                self.api.kill()
        self.api = None


# --------------------------------------------------------------------------- driving load

_project_seq = 0


def new_project(label):
    global _project_seq
    _project_seq += 1
    name = f"{label}-{int(time.time())}-{_project_seq}"
    return http("POST", "/api/projects", {"owner": "bench", "name": name})["id"]


def trigger(project_id, yaml):
    started = time.perf_counter()
    http("POST", f"/api/projects/{project_id}/runs",
         {"commitSha": "0123abc", "branch": "main", "pipelineYaml": yaml})
    return (time.perf_counter() - started) * 1000


def trigger_many(project_ids, yaml, count, clients):
    """Submits `count` runs from `clients` concurrent clients; returns per-request latencies (ms)."""
    with concurrent.futures.ThreadPoolExecutor(max_workers=clients) as pool:
        futures = [pool.submit(trigger, project_ids[i % len(project_ids)], yaml) for i in range(count)]
        return [f.result() for f in futures]


def wait_for_runs(project_ids, timeout):
    ids = ",".join(str(p) for p in project_ids)
    deadline = time.time() + timeout
    while time.time() < deadline:
        left = int(sql_value(f"SELECT count(*) FROM pipeline_run WHERE project_id IN ({ids}) "
                             f"AND status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')"))
        if left == 0:
            return
        time.sleep(0.5)
    raise SystemExit(f"runs did not finish within {timeout}s")


def job_rows(project_ids):
    ids = ",".join(str(p) for p in project_ids)
    rows = sql(f"""
        SELECT j.id, j.run_id, j.status, j.attempt,
               extract(epoch FROM j.available_at) * 1000,
               extract(epoch FROM j.started_at) * 1000,
               extract(epoch FROM j.finished_at) * 1000,
               (SELECT extract(epoch FROM max(dep.finished_at)) * 1000
                  FROM job_dependency d JOIN job dep ON dep.run_id = j.run_id AND dep.name = d.depends_on
                 WHERE d.job_id = j.id)
          FROM job j JOIN pipeline_run r ON r.id = j.run_id
         WHERE r.project_id IN ({ids})
    """)

    def num(v):
        return float(v) if v not in (None, "") else None

    return [{"id": int(r[0]), "run": int(r[1]), "status": r[2], "attempt": int(r[3]),
             "available": num(r[4]), "started": num(r[5]), "finished": num(r[6]), "deps_done": num(r[7])}
            for r in rows]


def run_rows(project_ids):
    ids = ",".join(str(p) for p in project_ids)
    rows = sql(f"""
        SELECT id, status, extract(epoch FROM created_at) * 1000, extract(epoch FROM finished_at) * 1000
          FROM pipeline_run WHERE project_id IN ({ids})
    """)
    return [{"id": int(r[0]), "status": r[1], "created": float(r[2]),
             "finished": float(r[3]) if r[3] else None} for r in rows]


def check_all_succeeded(project_ids):
    runs = run_rows(project_ids)
    bad = [r for r in runs if r["status"] != "SUCCEEDED"]
    if bad:
        raise SystemExit(f"{len(bad)} of {len(runs)} runs did not succeed: {bad[:3]}")
    return runs


# --------------------------------------------------------------------------- scenarios

def scenario_chain(label, runs, length):
    """Concurrent 5-job chains of no-op jobs: every millisecond is scheduling overhead."""
    project = new_project(f"chain-{label}")
    trigger_many([project], chain_pipeline(length), runs, clients=8)
    wait_for_runs([project], timeout=120)
    run_list = check_all_succeeded([project])
    jobs = job_rows([project])

    hops = [j["started"] - j["deps_done"] for j in jobs if j["deps_done"] is not None]
    first = [j["started"] - j["available"] for j in jobs if j["deps_done"] is None]
    # Measured on the database clock: created (first job available) -> run marked finished.
    created = {}
    for j in jobs:
        created[j["run"]] = min(created.get(j["run"], j["available"]), j["available"])
    e2e = [r["finished"] - created[r["id"]] for r in run_list]
    return {
        "runs": runs, "jobs_per_run": length,
        "hop_ms": summarize_ms(hops),
        "first_job_ms": summarize_ms(first),
        "run_e2e_ms": summarize_ms(e2e),
    }


def scenario_fanout(label, cluster, runs, jobs_per_run, seconds, retries=0, during=None):
    """Independent `sleep` jobs that saturate every slot: measures sustained throughput."""
    project = new_project(f"fanout-{label}")
    trigger_many([project], fanout_pipeline(jobs_per_run, seconds, retries), runs, clients=8)
    extra = during() if during else None
    wait_for_runs([project], timeout=600)
    run_list = check_all_succeeded([project])
    jobs = job_rows([project])

    first_start = min(j["started"] for j in jobs if j["started"])
    last_finish = max(j["finished"] for j in jobs)
    makespan_s = (last_finish - first_start) / 1000
    completed = len(jobs)
    slots = cluster.slots()
    ideal_s = completed * seconds / slots
    return {
        "workers": len(cluster.workers), "slots": slots,
        "jobs": completed, "job_seconds": seconds,
        "makespan_s": round(makespan_s, 2),
        "jobs_per_min": round(completed / makespan_s * 60),
        "ideal_s": round(ideal_s, 2),
        "efficiency_pct": round(ideal_s / makespan_s * 100, 1),
        "project": project, "extra": extra, "run_count": len(run_list),
    }


def scenario_burst(runs, clients):
    projects = [new_project(f"burst-{i}") for i in range(clients)]
    started = time.perf_counter()
    latencies = trigger_many(projects, chain_pipeline(1), runs, clients)
    submit_s = time.perf_counter() - started
    wait_for_runs(projects, timeout=300)
    drained_s = time.perf_counter() - started
    check_all_succeeded(projects)
    return {
        "runs": runs, "clients": clients,
        "submit_s": round(submit_s, 2),
        "requests_per_s": round(runs / submit_s),
        "api_latency_ms": summarize_ms(latencies),
        "all_done_s": round(drained_s, 2),
    }


def scenario_crash(cluster, victim):
    """kill -9 one of two workers while it holds running jobs."""
    state = {}

    def kill_victim():
        time.sleep(2)  # let both workers claim their first batch
        held = int(sql_value(f"SELECT count(*) FROM job WHERE status = 'RUNNING' AND worker_id = '{victim}'"))
        state["kill_ms"] = float(sql_value("SELECT extract(epoch FROM clock_timestamp()) * 1000"))
        cluster.stop_worker(victim, hard=True)
        state["held"] = held
        return state

    result = scenario_fanout("crash", cluster, runs=8, jobs_per_run=4, seconds=5, retries=2, during=kill_victim)
    jobs = job_rows([result["project"]])
    recovered = [j for j in jobs if j["attempt"] > 1]
    detect = [j["available"] - state["kill_ms"] for j in recovered]
    return {
        "runs": result["run_count"], "jobs": result["jobs"],
        "jobs_held_by_killed_worker": state["held"],
        "jobs_recovered": len(recovered),
        "runs_succeeded": result["run_count"],
        "lease_seconds": LEASE_SECONDS,
        "detect_s_max": round(max(detect) / 1000, 1) if detect else None,
        "detect_s_p50": round(percentile(detect, 50) / 1000, 1) if detect else None,
    }


# --------------------------------------------------------------------------- report

def machine_info():
    info = {"os": platform.platform(), "cpus": os.cpu_count(), "python": platform.python_version()}
    try:
        if sys.platform == "darwin":
            info["cpu"] = run(["sysctl", "-n", "machdep.cpu.brand_string"]).strip()
            info["memory_gb"] = round(int(run(["sysctl", "-n", "hw.memsize"])) / 2**30)
        java = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr
        info["java"] = next((l for l in java.splitlines() if "version" in l), "").strip()
    except Exception:
        pass
    return info


def write_report(results):
    (BENCH / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    m = results["machine"]
    base, wake = results["chain_interval"], results["chain_wake"]
    lines = [
        "# Conveyor CI load test results",
        "",
        f"Measured {results['date']} on {m.get('cpu', m['os'])}, {m['cpus']} CPUs"
        + (f", {m['memory_gb']} GB RAM" if m.get("memory_gb") else "")
        + ". API, workers, Postgres and Redis all on this one machine.",
        "Jobs use the simulated runtime (no containers), so these numbers are the engine's own overhead.",
        f"Workers have {SLOTS_PER_WORKER} slots each; lease = {LEASE_SECONDS} s. Reproduce with "
        "`python3 bench/loadtest.py`.",
        "",
        "## 1. Scheduling latency: event wake-ups vs. interval polling",
        "",
        f"{base['runs']} concurrent 5-job chains of no-op jobs. *Hop* = time from a job finishing to "
        "the job that depends on it starting on a worker.",
        "",
        "| | interval only (1 s) | with wake-ups | improvement |",
        "|---|---|---|---|",
    ]
    for label, key in [("Hop latency p50", ("hop_ms", "p50")), ("Hop latency p95", ("hop_ms", "p95")),
                       ("Hop latency p99", ("hop_ms", "p99")),
                       ("Trigger → first job running, p50", ("first_job_ms", "p50")),
                       ("5-job run end to end, p50", ("run_e2e_ms", "p50"))]:
        b, w = base[key[0]][key[1]], wake[key[0]][key[1]]
        if not b or not w:
            gain = "-"
        elif b / w >= 1.5:
            gain = f"{b / w:.0f}× faster"
        elif w / b >= 1.5:
            gain = f"{w / b:.1f}× slower"
        else:
            gain = "about the same"
        lines.append(f"| {label} | {fmt_ms(b)} | {fmt_ms(w)} | {gain} |")

    lines += [
        "",
        "## 2. Throughput and horizontal scaling",
        "",
        "Independent 1-second jobs saturating every slot. Efficiency = ideal time / actual time.",
        "",
        "| Workers | Slots | Jobs | Time | Jobs/min | Efficiency |",
        "|---|---|---|---|---|---|",
    ]
    for r in results["scaling"]:
        lines.append(f"| {r['workers']} | {r['slots']} | {r['jobs']} | {r['makespan_s']} s | "
                     f"{r['jobs_per_min']:,} | {r['efficiency_pct']}% |")

    b = results["burst"]
    lat = b["api_latency_ms"]
    c = results["crash"]
    lines += [
        "",
        "## 3. Burst of pushes",
        "",
        f"{b['runs']} runs submitted by {b['clients']} concurrent clients (one project each).",
        "",
        f"- Accepted at **{b['requests_per_s']} runs/s**; API latency p50 {fmt_ms(lat['p50'])}, "
        f"p95 {fmt_ms(lat['p95'])}, p99 {fmt_ms(lat['p99'])}",
        f"- All {b['runs']} runs finished {b['all_done_s']} s after the first request",
        "",
        "## 4. Crash recovery",
        "",
        f"Two workers; one is killed with `kill -9` while running {c['jobs_held_by_killed_worker']} jobs.",
        "",
        f"- {c['jobs_recovered']} orphaned jobs detected and re-queued within **{c['detect_s_max']} s** "
        f"(lease {c['lease_seconds']} s)",
        f"- **{c['runs_succeeded']}/{c['runs']} runs succeeded**: no job lost, no run failed",
        "",
    ]
    report = "\n".join(lines)
    (BENCH / "results.md").write_text(report)

    # Mirror the tables into the README between the bench markers, one heading level down.
    readme = ROOT / "README.md"
    start, end = "<!-- bench:start -->", "<!-- bench:end -->"
    text = readme.read_text()
    if start in text and end in text:
        body = report.split("\n", 2)[2]  # drop the title line
        body = "\n".join("#" + l if l.startswith("## ") else l for l in body.splitlines())
        head, rest = text.split(start, 1)
        readme.write_text(head + start + "\n" + body.strip() + "\n" + end + rest.split(end, 1)[1])


# --------------------------------------------------------------------------- main

def prepare(build):
    if build:
        log("Building the jar (mvn -q -DskipTests package)...")
        subprocess.run(["mvn", "-q", "-B", "-DskipTests", "package"], cwd=ROOT, check=True)
    jars = [j for j in glob.glob(str(ROOT / "target" / "conveyor-ci-*.jar")) if not j.endswith(".original")]
    if not jars:
        raise SystemExit("no jar in target/: run without --no-build")

    if not os.environ.get("BENCH_NO_COMPOSE"):
        log("Starting Postgres and Redis (docker compose up -d)...")
        subprocess.run(["docker", "compose", "up", "-d"], cwd=ROOT, check=True, capture_output=True)
    for _ in range(60):
        try:
            sql("SELECT 1", db="conveyor")
            break
        except subprocess.CalledProcessError:
            time.sleep(1)
    else:
        raise SystemExit("Postgres did not come up")

    log(f"Resetting the {DB_NAME} database and Redis db {REDIS_DB}...")
    sql(f"DROP DATABASE IF EXISTS {DB_NAME} WITH (FORCE)", db="conveyor")
    sql(f"CREATE DATABASE {DB_NAME}", db="conveyor")
    run(REDIS_CLI + ["-n", REDIS_DB, "FLUSHDB"])
    return Path(jars[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--no-build", action="store_true", help="use the existing jar in target/")
    parser.add_argument("--quick", action="store_true", help="smaller smoke run (~1 minute)")
    args = parser.parse_args()

    chain_runs = 8 if args.quick else 24
    fanout_runs = 4 if args.quick else 16   # x16 one-second jobs
    burst_runs = 100 if args.quick else 500

    jar = prepare(build=not args.no_build)
    cluster = Cluster(jar)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(1))
    results = {"date": time.strftime("%Y-%m-%d"), "machine": machine_info(), "scaling": []}
    all_workers = ["w1", "w2", "w3", "w4"]
    try:
        log("\n[1/4] Interval-only scheduling (baseline): API + 4 workers")
        cluster.start_api(wake=False)
        cluster.start_workers(all_workers)
        results["chain_interval"] = scenario_chain("interval", chain_runs, 5)
        log(f"      hop p50 {fmt_ms(results['chain_interval']['hop_ms']['p50'])}")
        cluster.stop_all()

        log("[2/4] Event wake-ups: API + 4 workers")
        cluster.start_api(wake=True)
        cluster.start_workers(all_workers)
        results["chain_wake"] = scenario_chain("wake", chain_runs, 5)
        log(f"      hop p50 {fmt_ms(results['chain_wake']['hop_ms']['p50'])}")
        r4 = scenario_fanout("4w", cluster, fanout_runs, 16, 1)
        log(f"      4 workers: {r4['jobs_per_min']:,} jobs/min, {r4['efficiency_pct']}% efficient")
        results["burst"] = scenario_burst(burst_runs, clients=16)
        log(f"      burst: {results['burst']['requests_per_s']} runs/s accepted")

        log("[3/4] Scaling down: 2 workers, then crash one of them")
        cluster.stop_worker("w4")
        cluster.stop_worker("w3")
        r2 = scenario_fanout("2w", cluster, fanout_runs, 16, 1)
        log(f"      2 workers: {r2['jobs_per_min']:,} jobs/min, {r2['efficiency_pct']}% efficient")
        results["crash"] = scenario_crash(cluster, victim="w2")
        log(f"      crash: {results['crash']['jobs_recovered']} jobs recovered in "
            f"<= {results['crash']['detect_s_max']} s, all runs succeeded")

        log("[4/4] 1 worker")
        r1 = scenario_fanout("1w", cluster, fanout_runs, 16, 1)
        log(f"      1 worker: {r1['jobs_per_min']:,} jobs/min, {r1['efficiency_pct']}% efficient")

        for r in (r1, r2, r4):
            r.pop("project", None)
            r.pop("extra", None)
            r.pop("run_count", None)
            results["scaling"].append(r)
    finally:
        cluster.stop_all()

    write_report(results)
    log(f"\nDone. Results: {BENCH / 'results.md'}\n")
    log((BENCH / "results.md").read_text())


if __name__ == "__main__":
    main()
