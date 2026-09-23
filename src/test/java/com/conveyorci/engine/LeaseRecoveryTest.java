package com.conveyorci.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.conveyorci.TestcontainersConfig;
import com.conveyorci.domain.Statuses.JobStatus;
import com.conveyorci.domain.Statuses.RunStatus;
import com.conveyorci.engine.JobStore.ClaimedJob;
import com.conveyorci.service.ProjectService;
import com.conveyorci.service.RunService;
import com.conveyorci.web.ApiModels.CreateProjectRequest;
import com.conveyorci.web.ApiModels.JobResponse;
import com.conveyorci.web.ApiModels.RunResponse;
import com.conveyorci.web.ApiModels.TriggerRunRequest;

/**
 * Crash recovery, tested deterministically: the built-in worker is disabled and the test plays
 * the part of the workers, including one that "crashes" by claiming a job and never heartbeating.
 */
@SpringBootTest(properties = "conveyor.worker.enabled=false")
@Import(TestcontainersConfig.class)
class LeaseRecoveryTest {

    @Autowired RunService runs;
    @Autowired ProjectService projects;
    @Autowired JobStore jobStore;

    private RunResponse trigger(int retries) {
        long projectId = projects.create(
                new CreateProjectRequest("vennela", "lease-" + UUID.randomUUID(), null)).id();
        return runs.trigger(projectId, new TriggerRunRequest("abcdef1", "main", """
                jobs:
                  build: { image: alpine, retries: %d, steps: [ { run: make } ] }
                """.formatted(retries)));
    }

    private JobResponse build(long runId) {
        return runs.get(runId).jobs().get(0);
    }

    @Test
    void crashedWorkersJobIsRequeuedAndTheZombieIsFencedOut() {
        RunResponse run = trigger(1);
        long jobId = run.jobs().get(0).id();

        // Worker A claims the job with a 1s lease, then "crashes" (never heartbeats).
        Optional<ClaimedJob> claimedByA = jobStore.claim(jobId, "worker-a", 1);
        assertThat(claimedByA).isPresent();
        assertThat(claimedByA.get().attempt()).isEqualTo(1);
        assertThat(jobStore.claim(jobId, "worker-b", 30)).as("a running job can't be claimed twice").isEmpty();

        // The scheduler notices the expired lease and puts the job back in the queue.
        await().atMost(Duration.ofSeconds(10)).until(() -> build(run.id()).status() == JobStatus.QUEUED);
        assertThat(build(run.id()).failureReason()).contains("lease expired");

        // Worker A comes back from the dead and tries to report success. It must be rejected.
        assertThat(jobStore.heartbeat(jobId, "worker-a", 1, 30)).isFalse();
        assertThat(jobStore.completeSuccess(jobId, "worker-a", 1)).isFalse();

        // Worker B picks the job up as attempt 2 and finishes it.
        Optional<ClaimedJob> claimedByB = jobStore.claim(jobId, "worker-b", 30);
        assertThat(claimedByB).isPresent();
        assertThat(claimedByB.get().attempt()).isEqualTo(2);
        assertThat(jobStore.completeSuccess(jobId, "worker-b", 2)).isTrue();

        await().atMost(Duration.ofSeconds(10)).until(() -> runs.get(run.id()).status() == RunStatus.SUCCEEDED);
    }

    @Test
    void expiredLeaseOnTheLastAttemptFailsTheJob() {
        RunResponse run = trigger(0);
        long jobId = run.jobs().get(0).id();

        assertThat(jobStore.claim(jobId, "worker-a", 1)).isPresent();

        await().atMost(Duration.ofSeconds(10)).until(() -> runs.get(run.id()).status() == RunStatus.FAILED);
        JobResponse build = build(run.id());
        assertThat(build.status()).isEqualTo(JobStatus.FAILED);
        assertThat(build.failureReason()).contains("lease expired");
    }

    @Test
    void failedAttemptWaitsForBackoffBeforeItCanBeClaimed() {
        RunResponse run = trigger(1);
        long jobId = run.jobs().get(0).id();

        assertThat(jobStore.claim(jobId, "worker-a", 30)).isPresent();
        assertThat(jobStore.completeFailure(jobId, "worker-a", 1, "exit 1", 60_000)).contains("QUEUED");

        // Still inside the 60s backoff window: nobody can claim it yet.
        assertThat(jobStore.claim(jobId, "worker-b", 30)).isEmpty();
        assertThat(build(run.id()).status()).isEqualTo(JobStatus.QUEUED);
    }
}
