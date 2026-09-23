package com.conveyorci.github;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Outbox for GitHub commit statuses. Rather than calling GitHub inline when a run changes state
 * (and losing the update if GitHub is briefly down), each GitHub-triggered run records the last
 * state it successfully reported. Any run whose current status maps to a different state is
 * pending delivery.
 *
 * <p>Claiming a row pushes its {@code next_report_at} a minute out, a short lease, so the HTTP call
 * to GitHub happens outside any transaction and two reporters never post for the same run at once.
 */
@Repository
public class CommitStatusStore {

    static final int MAX_ATTEMPTS = 10;

    private static final String DESIRED_STATE = """
            CASE r.status WHEN 'SUCCEEDED' THEN 'success'
                          WHEN 'FAILED' THEN 'failure'
                          WHEN 'CANCELLED' THEN 'error'
                          ELSE 'pending' END""";

    private final NamedParameterJdbcTemplate jdbc;

    public CommitStatusStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record PendingStatus(long runId, int runNumber, String state, String failureReason,
                                String commitSha, String owner, String repo) {
    }

    public List<PendingStatus> claimPending(int limit) {
        return jdbc.query("""
                UPDATE pipeline_run r SET next_report_at = now() + interval '60 seconds'
                  FROM project p
                 WHERE p.id = r.project_id
                   AND r.id IN (SELECT r.id FROM pipeline_run r
                                 WHERE r.source = 'GITHUB_PUSH'
                                   AND r.report_attempts < :maxAttempts
                                   AND (r.next_report_at IS NULL OR r.next_report_at <= now())
                                   AND r.reported_state IS DISTINCT FROM (%1$s)
                                 ORDER BY r.id
                                 LIMIT :limit
                                   FOR UPDATE SKIP LOCKED)
                RETURNING r.id, r.run_number, (%1$s) AS state, r.failure_reason, r.commit_sha, p.owner, p.name
                """.formatted(DESIRED_STATE),
                Map.of("limit", limit, "maxAttempts", MAX_ATTEMPTS),
                (rs, i) -> new PendingStatus(rs.getLong("id"), rs.getInt("run_number"), rs.getString("state"),
                        rs.getString("failure_reason"), rs.getString("commit_sha"), rs.getString("owner"),
                        rs.getString("name")));
    }

    /**
     * Records a successful post. If the run moved on meanwhile (e.g. we posted "pending" but it has
     * since succeeded), the states differ again and the next pass posts the new one.
     */
    public void markReported(long runId, String state) {
        jdbc.update("""
                UPDATE pipeline_run SET reported_state = :state, report_attempts = 0, next_report_at = NULL
                 WHERE id = :run
                """, Map.of("run", runId, "state", state));
    }

    public void markFailed(long runId, long retryInMillis) {
        jdbc.update("""
                UPDATE pipeline_run SET report_attempts = report_attempts + 1,
                                        next_report_at = now() + (:delay * interval '1 millisecond')
                 WHERE id = :run
                """, Map.of("run", runId, "delay", retryInMillis));
    }

    public int attempts(long runId) {
        Integer attempts = jdbc.queryForObject("SELECT report_attempts FROM pipeline_run WHERE id = :run",
                Map.of("run", runId), Integer.class);
        return attempts == null ? 0 : attempts;
    }
}
