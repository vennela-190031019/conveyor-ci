package com.conveyorci.engine;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * One scheduler pass, run in a single transaction:
 * <ol>
 *   <li>skip jobs whose dependencies failed (repeated so skips cascade down the DAG)</li>
 *   <li>promote jobs whose dependencies all succeeded: PENDING to QUEUED</li>
 *   <li>reap expired leases: RUNNING jobs whose worker stopped heartbeating go back to QUEUED
 *       (or FAILED if out of attempts)</li>
 *   <li>finalize runs whose jobs are all finished</li>
 *   <li>select QUEUED jobs to dispatch, including ones dispatched long ago but never claimed,
 *       in case the Redis message was lost</li>
 * </ol>
 * A transaction-scoped Postgres advisory lock makes this safe to run on several API instances:
 * only one pass runs at a time, and the lock is released automatically on commit or crash.
 */
@Repository
public class SchedulerStore {

    private static final Logger log = LoggerFactory.getLogger(SchedulerStore.class);
    static final long ADVISORY_LOCK_KEY = 7_345_001L;
    private static final int MAX_CASCADE_ROUNDS = 100;
    private static final int DISPATCH_BATCH = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final int redispatchSeconds;

    public SchedulerStore(NamedParameterJdbcTemplate jdbc,
                          @Value("${conveyor.scheduler.redispatch-seconds:30}") int redispatchSeconds) {
        this.jdbc = jdbc;
        this.redispatchSeconds = redispatchSeconds;
    }

    /** Returns the job ids to push to the queue after this transaction commits. */
    @Transactional
    public List<Long> tick() {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(:key)",
                Map.of("key", ADVISORY_LOCK_KEY), Boolean.class);
        if (!Boolean.TRUE.equals(locked)) {
            return List.of();
        }
        for (int round = 0; round < MAX_CASCADE_ROUNDS && skipBlockedJobs() > 0; round++) {
            // keep going until no newly skipped job blocks another
        }
        promoteReadyJobs();
        reapExpiredLeases();
        finalizeRuns();
        return selectForDispatch();
    }

    int skipBlockedJobs() {
        List<Long> skipped = jdbc.queryForList("""
                UPDATE job j SET status = 'SKIPPED', finished_at = now(),
                                 failure_reason = 'a dependency did not succeed'
                 WHERE j.status = 'PENDING'
                   AND EXISTS (SELECT 1 FROM job_dependency d
                                 JOIN job dep ON dep.run_id = j.run_id AND dep.name = d.depends_on
                                WHERE d.job_id = j.id AND dep.status IN ('FAILED', 'SKIPPED', 'CANCELLED'))
                RETURNING j.id
                """, Map.of(), Long.class);
        if (!skipped.isEmpty()) {
            jdbc.update("UPDATE step SET status = 'SKIPPED' WHERE job_id IN (:ids) AND status = 'PENDING'",
                    Map.of("ids", skipped));
        }
        return skipped.size();
    }

    int promoteReadyJobs() {
        return jdbc.update("""
                UPDATE job j SET status = 'QUEUED', available_at = now()
                 WHERE j.status = 'PENDING'
                   AND NOT EXISTS (SELECT 1 FROM job_dependency d
                                     JOIN job dep ON dep.run_id = j.run_id AND dep.name = d.depends_on
                                    WHERE d.job_id = j.id AND dep.status <> 'SUCCEEDED')
                """, Map.of());
    }

    int reapExpiredLeases() {
        record Reaped(long id, String status, String workerId) {
        }
        List<Reaped> reaped = jdbc.query("""
                UPDATE job j
                   SET status = CASE WHEN j.attempt < j.max_attempts THEN 'QUEUED' ELSE 'FAILED' END,
                       available_at = now(),
                       finished_at = CASE WHEN j.attempt < j.max_attempts THEN NULL ELSE now() END,
                       worker_id = NULL, lease_expires_at = NULL, enqueued_at = NULL,
                       failure_reason = 'worker lease expired: the worker crashed or lost connectivity'
                  FROM job old
                 WHERE old.id = j.id AND j.status = 'RUNNING' AND j.lease_expires_at < now()
                RETURNING j.id, j.status, old.worker_id
                """, Map.of(), (rs, i) -> new Reaped(rs.getLong("id"), rs.getString("status"),
                rs.getString("worker_id")));

        List<Long> failed = reaped.stream().filter(r -> "FAILED".equals(r.status())).map(Reaped::id).toList();
        if (!failed.isEmpty()) {
            jdbc.update("UPDATE step SET status = 'FAILED' WHERE job_id IN (:ids) AND status = 'RUNNING'",
                    Map.of("ids", failed));
            jdbc.update("UPDATE step SET status = 'SKIPPED' WHERE job_id IN (:ids) AND status = 'PENDING'",
                    Map.of("ids", failed));
        }
        reaped.forEach(r -> log.warn("lease expired for job {} held by worker {}; job is now {}",
                r.id(), r.workerId(), r.status()));
        return reaped.size();
    }

    int finalizeRuns() {
        return jdbc.update("""
                UPDATE pipeline_run r
                   SET status = CASE WHEN EXISTS (SELECT 1 FROM job j
                                                  WHERE j.run_id = r.id AND j.status IN ('FAILED', 'SKIPPED'))
                                     THEN 'FAILED' ELSE 'SUCCEEDED' END,
                       started_at = COALESCE(r.started_at, now()),
                       finished_at = now()
                 WHERE r.status IN ('QUEUED', 'RUNNING')
                   AND NOT EXISTS (SELECT 1 FROM job j
                                    WHERE j.run_id = r.id AND j.status IN ('PENDING', 'QUEUED', 'RUNNING'))
                """, Map.of());
    }

    List<Long> selectForDispatch() {
        return jdbc.queryForList("""
                UPDATE job SET enqueued_at = now()
                 WHERE id IN (SELECT id FROM job
                               WHERE status = 'QUEUED' AND available_at <= now()
                                 AND (enqueued_at IS NULL
                                      OR enqueued_at < now() - (:redispatch * interval '1 second'))
                               ORDER BY id
                               LIMIT :batch
                                 FOR UPDATE SKIP LOCKED)
                RETURNING id
                """, Map.of("redispatch", redispatchSeconds, "batch", DISPATCH_BATCH), Long.class);
    }
}
