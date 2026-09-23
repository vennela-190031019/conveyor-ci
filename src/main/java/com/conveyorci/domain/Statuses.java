package com.conveyorci.domain;

/** Status enums for runs, jobs and steps. Stored as strings so new values don't break old rows. */
public final class Statuses {

    private Statuses() {
    }

    public enum RunStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /**
     * PENDING: waiting on dependencies. QUEUED: runnable, waiting for a worker.
     * SKIPPED: a dependency failed, so this job will never run.
     */
    public enum JobStatus { PENDING, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, SKIPPED }

    public enum StepStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }

    /** How a run was started. */
    public enum RunSource { API, GITHUB_PUSH }
}
