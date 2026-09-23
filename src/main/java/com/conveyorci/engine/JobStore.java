package com.conveyorci.engine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.conveyorci.domain.Statuses.StepStatus;

/**
 * Worker-side state transitions, written as single SQL statements so each one is atomic.
 *
 * <p><b>Fencing:</b> every write a worker makes after claiming is conditioned on
 * {@code (worker_id, attempt, status = RUNNING)}. If the worker's lease expired and the job was
 * handed to someone else, or the run was cancelled, the condition fails and the stale
 * ("zombie") worker's writes are silently rejected. It can never overwrite the new owner's result.
 *
 * <p>All times come from the database clock ({@code now()}), so clock skew between worker
 * machines can't make a lease look alive or expired when it isn't.
 */
@Repository
public class JobStore {

    private static final int MAX_REASON_CHARS = 500;

    private static final String OWNED_BY_CALLER =
            "j.id = :job AND j.worker_id = :worker AND j.attempt = :attempt AND j.status = 'RUNNING'";

    private final NamedParameterJdbcTemplate jdbc;

    public JobStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StepSpec(long id, int position, String name, String command) {
    }

    /** Repository to download into /workspace before the first step. */
    public record Checkout(String owner, String repo, String sha) {
    }

    /** @param checkout null when the run doesn't check out code */
    public record ClaimedJob(long id, long runId, int attempt, int maxAttempts, String image,
                             int timeoutMinutes, Checkout checkout, List<StepSpec> steps) {
    }

    public record StepLog(int position, String name, String status, Integer exitCode, String log) {
    }

    public record WorkerInfo(String id, String hostname, int concurrency, Instant startedAt,
                             Instant lastHeartbeatAt, boolean alive) {
    }

    /**
     * Atomically takes ownership of a QUEUED job. Returns empty if another worker got it first,
     * the job was cancelled, or it's still in retry backoff. This is what makes duplicate queue
     * messages harmless.
     */
    @Transactional
    public Optional<ClaimedJob> claim(long jobId, String workerId, int leaseSeconds) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("job", jobId)
                .addValue("worker", workerId)
                .addValue("lease", leaseSeconds);

        List<ClaimedJob> claimed = jdbc.query("""
                UPDATE job j
                   SET status = 'RUNNING', worker_id = :worker, attempt = j.attempt + 1,
                       lease_expires_at = now() + (:lease * interval '1 second'),
                       started_at = now(), finished_at = NULL, failure_reason = NULL
                  FROM pipeline_run r
                  JOIN project p ON p.id = r.project_id
                 WHERE r.id = j.run_id
                   AND j.id = :job AND j.status = 'QUEUED' AND j.available_at <= now()
                RETURNING j.id, j.run_id, j.attempt, j.max_attempts, j.image, j.timeout_minutes,
                          r.checkout, r.commit_sha, p.owner, p.name
                """, params, (rs, i) -> new ClaimedJob(rs.getLong("id"), rs.getLong("run_id"),
                rs.getInt("attempt"), rs.getInt("max_attempts"), rs.getString("image"),
                rs.getInt("timeout_minutes"),
                rs.getBoolean("checkout")
                        ? new Checkout(rs.getString("owner"), rs.getString("name"), rs.getString("commit_sha"))
                        : null,
                List.of()));
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimedJob job = claimed.get(0);

        // A retry starts from a clean slate.
        jdbc.update("""
                UPDATE step SET status = 'PENDING', exit_code = NULL, log = NULL,
                                started_at = NULL, finished_at = NULL
                 WHERE job_id = :job
                """, params);
        jdbc.update("""
                UPDATE pipeline_run SET status = 'RUNNING', started_at = COALESCE(started_at, now())
                 WHERE id = :run AND status = 'QUEUED'
                """, Map.of("run", job.runId()));

        List<StepSpec> steps = jdbc.query(
                "SELECT id, position, name, command FROM step WHERE job_id = :job ORDER BY position",
                params, (rs, i) -> new StepSpec(rs.getLong("id"), rs.getInt("position"),
                        rs.getString("name"), rs.getString("command")));

        return Optional.of(new ClaimedJob(job.id(), job.runId(), job.attempt(), job.maxAttempts(),
                job.image(), job.timeoutMinutes(), job.checkout(), steps));
    }

    /** Extends the lease. Returns false if the caller no longer owns the job (lease lost or run cancelled). */
    public boolean heartbeat(long jobId, String workerId, int attempt, int leaseSeconds) {
        return jdbc.update("UPDATE job j SET lease_expires_at = now() + (:lease * interval '1 second') WHERE "
                + OWNED_BY_CALLER, owner(jobId, workerId, attempt).addValue("lease", leaseSeconds)) == 1;
    }

    public boolean startStep(long stepId, long jobId, String workerId, int attempt) {
        return jdbc.update("""
                UPDATE step s SET status = 'RUNNING', started_at = now()
                  FROM job j
                 WHERE s.id = :step AND s.job_id = j.id AND\s""" + OWNED_BY_CALLER,
                owner(jobId, workerId, attempt).addValue("step", stepId)) == 1;
    }

    public boolean finishStep(long stepId, long jobId, String workerId, int attempt, StepStatus status,
                              Integer exitCode, String log) {
        return jdbc.update("""
                UPDATE step s SET status = :status, exit_code = :exit, log = :log, finished_at = now()
                  FROM job j
                 WHERE s.id = :step AND s.job_id = j.id AND\s""" + OWNED_BY_CALLER,
                owner(jobId, workerId, attempt)
                        .addValue("step", stepId)
                        .addValue("status", status.name())
                        .addValue("exit", exitCode, Types.INTEGER)
                        .addValue("log", log)) == 1;
    }

    public boolean completeSuccess(long jobId, String workerId, int attempt) {
        return jdbc.update("""
                UPDATE job j SET status = 'SUCCEEDED', finished_at = now(), lease_expires_at = NULL
                 WHERE\s""" + OWNED_BY_CALLER, owner(jobId, workerId, attempt)) == 1;
    }

    /**
     * Records a failed attempt. If attempts remain, the job goes back to QUEUED but only becomes
     * claimable after {@code backoffMillis}; otherwise it is FAILED for good.
     *
     * @return the job's new status, or empty if the caller no longer owned the job
     */
    @Transactional
    public Optional<String> completeFailure(long jobId, String workerId, int attempt, String reason,
                                            long backoffMillis) {
        List<String> status = jdbc.queryForList("""
                UPDATE job j
                   SET status = CASE WHEN j.attempt < j.max_attempts THEN 'QUEUED' ELSE 'FAILED' END,
                       available_at = CASE WHEN j.attempt < j.max_attempts
                                           THEN now() + (:backoff * interval '1 millisecond')
                                           ELSE j.available_at END,
                       finished_at = CASE WHEN j.attempt < j.max_attempts THEN NULL ELSE now() END,
                       worker_id = NULL, lease_expires_at = NULL, enqueued_at = NULL,
                       failure_reason = :reason
                 WHERE\s""" + OWNED_BY_CALLER + " RETURNING j.status",
                owner(jobId, workerId, attempt)
                        .addValue("reason", truncate(reason))
                        .addValue("backoff", backoffMillis), String.class);
        if (status.isEmpty()) {
            return Optional.empty();
        }
        if ("FAILED".equals(status.get(0))) {
            jdbc.update("UPDATE step SET status = 'SKIPPED' WHERE job_id = :job AND status = 'PENDING'",
                    Map.of("job", jobId));
        }
        return Optional.of(status.get(0));
    }

    /**
     * Cancels every unfinished job in the run. A worker currently running one of them finds out at
     * its next heartbeat (the fencing condition fails) and kills its container.
     *
     * @return false if the run had already finished
     */
    @Transactional
    public boolean cancelRun(long runId) {
        Map<String, Object> params = Map.of("run", runId);
        int updated = jdbc.update("""
                UPDATE pipeline_run SET status = 'CANCELLED', finished_at = now()
                 WHERE id = :run AND status IN ('QUEUED', 'RUNNING')
                """, params);
        if (updated == 0) {
            return false;
        }
        jdbc.update("""
                UPDATE job SET status = 'CANCELLED', finished_at = now(), lease_expires_at = NULL,
                               failure_reason = 'run was cancelled'
                 WHERE run_id = :run AND status IN ('PENDING', 'QUEUED', 'RUNNING')
                """, params);
        jdbc.update("""
                UPDATE step s SET status = 'SKIPPED'
                  FROM job j
                 WHERE s.job_id = j.id AND j.run_id = :run AND s.status IN ('PENDING', 'RUNNING')
                """, params);
        return true;
    }

    public Optional<List<StepLog>> logs(long jobId) {
        Integer jobs = jdbc.queryForObject("SELECT count(*) FROM job WHERE id = :job",
                Map.of("job", jobId), Integer.class);
        if (jobs == null || jobs == 0) {
            return Optional.empty();
        }
        return Optional.of(jdbc.query("""
                SELECT position, name, status, exit_code, log FROM step
                 WHERE job_id = :job ORDER BY position
                """, Map.of("job", jobId), (rs, i) -> new StepLog(rs.getInt("position"),
                rs.getString("name"), rs.getString("status"), (Integer) rs.getObject("exit_code"),
                rs.getString("log"))));
    }

    public void registerWorker(String workerId, String hostname, int concurrency) {
        jdbc.update("""
                INSERT INTO worker (id, hostname, concurrency, started_at, last_heartbeat_at)
                VALUES (:id, :host, :concurrency, now(), now())
                ON CONFLICT (id) DO UPDATE SET last_heartbeat_at = now()
                """, Map.of("id", workerId, "host", hostname, "concurrency", concurrency));
    }

    public List<WorkerInfo> listWorkers() {
        return jdbc.query("""
                SELECT id, hostname, concurrency, started_at, last_heartbeat_at,
                       last_heartbeat_at > now() - interval '15 seconds' AS alive
                  FROM worker ORDER BY started_at DESC LIMIT 100
                """, Map.of(), (rs, i) -> new WorkerInfo(rs.getString("id"), rs.getString("hostname"),
                rs.getInt("concurrency"), instant(rs, "started_at"), instant(rs, "last_heartbeat_at"),
                rs.getBoolean("alive")));
    }

    private static MapSqlParameterSource owner(long jobId, String workerId, int attempt) {
        return new MapSqlParameterSource()
                .addValue("job", jobId)
                .addValue("worker", workerId)
                .addValue("attempt", attempt);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= MAX_REASON_CHARS) {
            return reason;
        }
        return reason.substring(0, MAX_REASON_CHARS - 3) + "...";
    }
}
