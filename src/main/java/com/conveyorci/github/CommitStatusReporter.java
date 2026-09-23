package com.conveyorci.github;

import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.conveyorci.engine.Backoff;
import com.conveyorci.github.CommitStatusStore.PendingStatus;

/**
 * Delivers commit statuses from the {@link CommitStatusStore} outbox to GitHub:
 * pending while a run is queued or running, then success / failure / error.
 * Failed posts are retried with exponential backoff, up to 10 attempts.
 */
@Component
@ConditionalOnProperty(name = "conveyor.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class CommitStatusReporter {

    private static final Logger log = LoggerFactory.getLogger(CommitStatusReporter.class);
    static final String CONTEXT = "conveyor-ci";

    private final CommitStatusStore store;
    private final GitHubClient github;
    private final String publicUrl;
    private final long retryBaseMillis;

    public CommitStatusReporter(CommitStatusStore store, GitHubClient github,
                                @Value("${conveyor.public-url:http://localhost:8080}") String publicUrl,
                                @Value("${conveyor.github.status-retry-base-ms:2000}") long retryBaseMillis) {
        this.store = store;
        this.github = github;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.retryBaseMillis = retryBaseMillis;
    }

    @Scheduled(fixedDelayString = "${conveyor.github.status-interval-ms:2000}")
    public void report() {
        try {
            for (PendingStatus status : store.claimPending(50)) {
                deliver(status);
            }
        } catch (RuntimeException e) {
            log.warn("commit status pass failed: {}", e.getMessage());
        }
    }

    private void deliver(PendingStatus status) {
        try {
            github.createStatus(status.owner(), status.repo(), status.commitSha(), status.state(),
                    describe(status), publicUrl + "/api/runs/" + status.runId(), CONTEXT);
            store.markReported(status.runId(), status.state());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            int attempt = store.attempts(status.runId()) + 1;
            store.markFailed(status.runId(), Backoff.delayMillis(attempt, retryBaseMillis, 5 * 60_000L, 0.2,
                    () -> ThreadLocalRandom.current().nextDouble()));
            log.warn("could not report '{}' for run {} (attempt {}/{}): {}", status.state(), status.runId(),
                    attempt, CommitStatusStore.MAX_ATTEMPTS, e.getMessage());
        }
    }

    static String describe(PendingStatus status) {
        String run = "Run #" + status.runNumber();
        return switch (status.state()) {
            case "success" -> run + " passed";
            case "failure" -> status.failureReason() != null
                    ? run + " failed: " + status.failureReason() : run + " failed";
            case "error" -> run + " was cancelled";
            default -> run + " is running";
        };
    }
}
