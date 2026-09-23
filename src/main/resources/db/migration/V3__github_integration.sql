-- Phase 3: GitHub integration.

-- Where a run came from, and whether workers should check out the repository's code.
ALTER TABLE pipeline_run ADD COLUMN source          VARCHAR(20) NOT NULL DEFAULT 'API';
ALTER TABLE pipeline_run ADD COLUMN checkout        BOOLEAN NOT NULL DEFAULT false;
-- Set when a run fails before any job runs (e.g. an invalid .conveyor.yml was pushed).
ALTER TABLE pipeline_run ADD COLUMN failure_reason  VARCHAR(500);

-- Commit-status outbox. A run's desired GitHub state is derived from its status; the reporter
-- sends it whenever it differs from reported_state, retrying with backoff if GitHub is down.
ALTER TABLE pipeline_run ADD COLUMN reported_state  VARCHAR(20);
ALTER TABLE pipeline_run ADD COLUMN report_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pipeline_run ADD COLUMN next_report_at  TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_run_status_report ON pipeline_run (next_report_at) WHERE source = 'GITHUB_PUSH';

-- GitHub retries deliveries; the delivery id makes webhook handling idempotent.
CREATE TABLE webhook_delivery (
    id           VARCHAR(100) PRIMARY KEY,
    event        VARCHAR(50)  NOT NULL,
    outcome      VARCHAR(255) NOT NULL,
    run_id       BIGINT REFERENCES pipeline_run (id) ON DELETE SET NULL,
    received_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
