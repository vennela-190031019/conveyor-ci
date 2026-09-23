-- Phase 2: execution engine.
--
-- Postgres is the source of truth for job state. Redis only carries "job N is ready"
-- messages, so a lost or duplicated message can never corrupt state: a worker must
-- win an atomic UPDATE (the claim) before it runs anything.

ALTER TABLE job ADD COLUMN worker_id        VARCHAR(100);
ALTER TABLE job ADD COLUMN lease_expires_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE job ADD COLUMN available_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE job ADD COLUMN enqueued_at      TIMESTAMP WITH TIME ZONE;
ALTER TABLE job ADD COLUMN failure_reason   VARCHAR(500);

ALTER TABLE step ADD COLUMN log TEXT;

-- Scheduler access paths: runnable jobs, and running jobs whose lease has expired.
DROP INDEX idx_job_status;
CREATE INDEX idx_job_dispatch ON job (status, available_at);
CREATE INDEX idx_job_lease ON job (lease_expires_at) WHERE status = 'RUNNING';

-- Worker registry, refreshed by each worker's heartbeat.
CREATE TABLE worker (
    id                 VARCHAR(100) PRIMARY KEY,
    hostname           VARCHAR(255) NOT NULL,
    concurrency        INTEGER NOT NULL,
    started_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    last_heartbeat_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
