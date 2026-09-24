# Conveyor CI load test results

Measured 2026-09-23 on Apple M1, 8 CPUs, 8 GB RAM. API, workers, Postgres and Redis all on this one machine.
Jobs use the simulated runtime (no containers), so these numbers are the engine's own overhead.
Workers have 8 slots each; lease = 10 s. Reproduce with `python3 bench/loadtest.py`.

## 1. Scheduling latency: event wake-ups vs. interval polling

24 concurrent 5-job chains of no-op jobs. *Hop* = time from a job finishing to the job that depends on it starting on a worker.

| | interval only (1 s) | with wake-ups | improvement |
|---|---|---|---|
| Hop latency p50 | 624 ms | 81 ms | 8× faster |
| Hop latency p95 | 1.08 s | 188 ms | 6× faster |
| Hop latency p99 | 1.13 s | 262 ms | 4× faster |
| Trigger → first job running, p50 | 2.71 s | 968 ms | 3× faster |
| 5-job run end to end, p50 | 8.01 s | 1.78 s | 4× faster |

## 2. Throughput and horizontal scaling

Independent 1-second jobs saturating every slot. Efficiency = ideal time / actual time.

| Workers | Slots | Jobs | Time | Jobs/min | Efficiency |
|---|---|---|---|---|---|
| 1 | 8 | 256 | 33.27 s | 462 | 96.2% |
| 2 | 16 | 256 | 17.79 s | 863 | 89.9% |
| 4 | 32 | 256 | 9.48 s | 1,620 | 84.4% |

## 3. Burst of pushes

500 runs submitted by 16 concurrent clients (one project each).

- Accepted at **94 runs/s**; API latency p50 160 ms, p95 248 ms, p99 326 ms
- All 500 runs finished 5.92 s after the first request

## 4. Crash recovery

Two workers; one is killed with `kill -9` while running 8 jobs.

- 8 orphaned jobs detected and re-queued within **7.6 s** (lease 10 s)
- **8/8 runs succeeded**: no job lost, no run failed
