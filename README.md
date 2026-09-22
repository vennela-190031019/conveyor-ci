# Conveyor CI

A self-hosted CI/CD platform: pipelines defined in YAML, jobs scheduled as a dependency DAG,
executed in Docker containers by a pool of workers, with live logs and GitHub integration.

> **Status: Phase 1 of 6 complete.** The API, data model and pipeline parser are done.
> Job execution arrives in Phase 2.

## Phase 1: what works today

- **Pipeline parser**: YAML to a validated DAG. It reports *every* error in one pass: unknown
  keys (catches typos like `step:`), missing fields, out-of-range values, self-dependencies,
  unknown dependencies, and **cycles with the full path** (`a -> b -> c -> a`).
- **Stage computation**: each job's stage is its longest dependency chain, so jobs in the
  same stage can run in parallel.
- **Safe YAML loading**: `SafeConstructor` (no arbitrary object construction), a 64 KB size
  cap, and alias/nesting limits against "billion laughs" payloads. Duplicate keys are rejected.
- **Runs API**: triggering a run persists the whole job/step graph in one transaction. Root
  jobs start `QUEUED` and dependent jobs start `PENDING`.
- **Concurrency-safe run numbers**: per-project `#1, #2, ...` sequence allocated under a row
  lock (`SELECT ... FOR UPDATE`), backed by a unique constraint. Tested with 8 concurrent triggers.
- **Postgres schema via Flyway**; Hibernate only *validates* it.
- **Integration tests against real Postgres** (Testcontainers), not an in-memory substitute.

## Architecture (Phase 1)

```
HTTP ──► Controllers ──► Services ──► JPA ──► Postgres (Flyway-managed)
                           │
                           └──► PipelineParser ──► PipelineDefinitionReader ──► PipelineGraph
                                (SnakeYAML, safe)   (structure + field rules)    (DAG checks, stages)
```

Data model: `project 1─* pipeline_run 1─* job 1─* step`, plus `job_dependency`.
Each run stores a snapshot of the YAML it was created from, so later edits never change
what an old run did.

## Pipeline format

```yaml
name: build-test-deploy
jobs:
  build:
    image: maven:3.9-eclipse-temurin-21
    steps:
      - name: Compile
        run: mvn -B compile
  unit-tests:
    image: maven:3.9-eclipse-temurin-21
    needs: build            # a job name or a list of job names
    retries: 1              # 0-5, default 0
    timeout_minutes: 15     # 1-360, default 30
    steps:
      - run: mvn -B test
  deploy:
    image: alpine:3.20
    needs: [unit-tests]
    steps:
      - run: ./deploy.sh
```

See [`examples/pipeline.yml`](examples/pipeline.yml).

## Run locally

Requirements: Java 21, Maven 3.9+, Docker.

```bash
docker compose up -d            # Postgres (+ Redis for Phase 2)
mvn spring-boot:run             # API on http://localhost:8080
mvn verify                      # unit + integration tests (needs Docker running)
```

## API

| Method | Path | Description |
|---|---|---|
| POST | `/api/projects` | Create a project `{owner, name, defaultBranch?}` |
| GET | `/api/projects` | List projects |
| GET | `/api/projects/{id}` | Get a project |
| POST | `/api/projects/{id}/runs` | Trigger a run `{commitSha, branch, pipelineYaml}` |
| GET | `/api/projects/{id}/runs` | Latest 50 runs, newest first |
| GET | `/api/runs/{id}` | Run with stages, jobs and steps |
| POST | `/api/pipelines/validate` | Dry-run validation (body: raw YAML) |

Errors use RFC 9457 `application/problem+json`: `400` for bad requests, `404` for missing
resources, `409` for duplicates, and `422` for an invalid pipeline, with an `errors` array.

```bash
curl -s -X POST localhost:8080/api/projects -H 'Content-Type: application/json' \
  -d '{"owner":"vennela","name":"demo"}'

curl -s -X POST localhost:8080/api/pipelines/validate -H 'Content-Type: application/yaml' \
  --data-binary @examples/pipeline.yml

jq -n --rawfile y examples/pipeline.yml '{commitSha:"a1b2c3d", branch:"main", pipelineYaml:$y}' |
  curl -s -X POST localhost:8080/api/projects/1/runs -H 'Content-Type: application/json' -d @-
```

## Design decisions

- **Collect all validation errors, not the first one.** A user fixing a pipeline should see
  every problem in one round trip.
- **Strict unknown-key rejection.** A typo like `step:` would otherwise silently produce a job
  with no steps.
- **Parse before locking.** An invalid pipeline fails fast and never holds the project row lock.
- **Status stored as strings**, not ordinals, so adding a status never corrupts existing rows.
- **Pure-Java core** (`PipelineDefinitionReader`, `PipelineGraph`) with no framework
  dependencies, so it's fast to test and reusable by workers.

## Roadmap

1. ~~Core API, data model, YAML to DAG parser~~
2. Redis job queue, Docker workers, heartbeats and lease-based recovery, retries with backoff
3. GitHub webhooks (HMAC-verified), commit statuses, OAuth login
4. Live log streaming (Redis pub/sub to WebSockets) and a React dashboard
5. AWS deployment; the platform runs its own CI
6. Load testing and published numbers (throughput, queue latency, recovery time)
