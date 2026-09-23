package com.conveyorci.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.conveyorci.TestcontainersConfig;
import com.conveyorci.domain.Statuses.JobStatus;
import com.conveyorci.domain.Statuses.RunStatus;
import com.conveyorci.domain.Statuses.StepStatus;
import com.conveyorci.service.ProjectService;
import com.conveyorci.service.RunService;
import com.conveyorci.web.ApiModels.CreateProjectRequest;
import com.conveyorci.web.ApiModels.JobResponse;
import com.conveyorci.web.ApiModels.RunResponse;
import com.conveyorci.web.ApiModels.TriggerRunRequest;

/** End-to-end engine tests: real Postgres + Redis, real scheduler and worker, fake containers. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class EngineIntegrationTest {

    @Autowired RunService runs;
    @Autowired ProjectService projects;
    @Autowired FakeContainerRuntime runtime;
    @Autowired JobStore jobStore;

    private RunResponse trigger(String yaml) {
        long projectId = projects.create(
                new CreateProjectRequest("vennela", "engine-" + UUID.randomUUID(), null)).id();
        return runs.trigger(projectId, new TriggerRunRequest("abcdef1", "main", yaml));
    }

    private RunResponse awaitRun(long runId, RunStatus expected) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50))
                .until(() -> runs.get(runId).status() == expected);
        return runs.get(runId);
    }

    private static JobResponse job(RunResponse run, String name) {
        return run.jobs().stream().filter(j -> j.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void runsEveryJobInDependencyOrder() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        RunResponse run = awaitRun(trigger("""
                jobs:
                  build:  { image: alpine, steps: [ { name: Compile, run: "echo build-%1$s" } ] }
                  test:   { image: alpine, needs: build, steps: [ { run: "echo test-%1$s" } ] }
                  lint:   { image: alpine, needs: build, steps: [ { run: "echo lint-%1$s" } ] }
                  deploy: { image: alpine, needs: [test, lint], steps: [ { run: "echo deploy-%1$s" } ] }
                """.formatted(tag)).id(), RunStatus.SUCCEEDED);

        assertThat(run.startedAt()).isNotNull();
        assertThat(run.finishedAt()).isNotNull();
        for (JobResponse job : run.jobs()) {
            assertThat(job.status()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(job.attempt()).isEqualTo(1);
            assertThat(job.steps()).allSatisfy(s -> {
                assertThat(s.status()).isEqualTo(StepStatus.SUCCEEDED);
                assertThat(s.exitCode()).isZero();
            });
        }

        List<String> order = runtime.executedCommands().stream().filter(c -> c.endsWith(tag)).toList();
        assertThat(order.indexOf("echo build-" + tag)).isLessThan(order.indexOf("echo test-" + tag));
        assertThat(order.indexOf("echo build-" + tag)).isLessThan(order.indexOf("echo lint-" + tag));
        assertThat(order.indexOf("echo test-" + tag)).isLessThan(order.indexOf("echo deploy-" + tag));
        assertThat(order.indexOf("echo lint-" + tag)).isLessThan(order.indexOf("echo deploy-" + tag));

        assertThat(runs.logs(job(run, "build").id()))
                .contains("==> Step 1: Compile [SUCCEEDED, exit 0]")
                .contains("$ echo build-" + tag)
                .contains("ran: echo build-" + tag);
    }

    @Test
    void failingJobIsRetriedThenFailsAndSkipsDownstream() {
        RunResponse run = awaitRun(trigger("""
                jobs:
                  build:
                    image: alpine
                    retries: 1
                    steps:
                      - run: exit 3
                      - run: echo never-runs
                  deploy: { image: alpine, needs: build, steps: [ { run: echo deploy } ] }
                """).id(), RunStatus.FAILED);

        JobResponse build = job(run, "build");
        assertThat(build.status()).isEqualTo(JobStatus.FAILED);
        assertThat(build.attempt()).isEqualTo(2);
        assertThat(build.failureReason()).contains("exited with code 3");
        assertThat(build.steps().get(0).status()).isEqualTo(StepStatus.FAILED);
        assertThat(build.steps().get(0).exitCode()).isEqualTo(3);
        assertThat(build.steps().get(1).status()).isEqualTo(StepStatus.SKIPPED);

        JobResponse deploy = job(run, "deploy");
        assertThat(deploy.status()).isEqualTo(JobStatus.SKIPPED);
        assertThat(deploy.attempt()).isZero();
        assertThat(deploy.steps()).allSatisfy(s -> assertThat(s.status()).isEqualTo(StepStatus.SKIPPED));
    }

    @Test
    void flakyJobSucceedsOnRetry() {
        String flaky = "flaky-" + UUID.randomUUID();
        RunResponse run = awaitRun(trigger("""
                jobs:
                  test: { image: alpine, retries: 2, steps: [ { run: "%s" } ] }
                """.formatted(flaky)).id(), RunStatus.SUCCEEDED);

        JobResponse test = job(run, "test");
        assertThat(test.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(test.attempt()).isEqualTo(2);
        assertThat(runs.logs(test.id())).contains("flaky run #2").doesNotContain("flaky run #1");
    }

    @Test
    void cancellingARunKillsItsRunningJob() {
        RunResponse started = trigger("""
                jobs:
                  slow: { image: alpine, steps: [ { run: hang } ] }
                  after: { image: alpine, needs: slow, steps: [ { run: echo after } ] }
                """);
        long slowId = job(started, "slow").id();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> job(runs.get(started.id()), "slow").status() == JobStatus.RUNNING);

        RunResponse cancelled = runs.cancel(started.id());
        assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(job(cancelled, "slow").status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job(cancelled, "after").status()).isEqualTo(JobStatus.CANCELLED);

        // The worker notices at its next heartbeat and kills the container.
        await().atMost(Duration.ofSeconds(10)).until(() -> runtime.wasRemoved("conveyor-job-" + slowId + "-1"));
        assertThat(runs.get(started.id()).status()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void workerRegistersAndHeartbeats() {
        await().atMost(Duration.ofSeconds(10)).until(() -> !jobStore.listWorkers().isEmpty());
        assertThat(jobStore.listWorkers()).anySatisfy(w -> {
            assertThat(w.alive()).isTrue();
            assertThat(w.concurrency()).isEqualTo(4);
        });
    }
}
