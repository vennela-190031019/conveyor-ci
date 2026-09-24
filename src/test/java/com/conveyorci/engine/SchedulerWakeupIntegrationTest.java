package com.conveyorci.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.conveyorci.TestcontainersConfig;
import com.conveyorci.domain.Statuses.RunStatus;
import com.conveyorci.service.ProjectService;
import com.conveyorci.service.RunService;
import com.conveyorci.web.ApiModels.CreateProjectRequest;
import com.conveyorci.web.ApiModels.TriggerRunRequest;

/**
 * The interval pass is pushed out to 10 minutes, so the only thing that can move this pipeline
 * along is event wake-ups: run created, then each job finishing.
 */
@SpringBootTest(properties = "conveyor.scheduler.interval-ms=600000")
@Import(TestcontainersConfig.class)
class SchedulerWakeupIntegrationTest {

    @Autowired RunService runs;
    @Autowired ProjectService projects;

    @Test
    void wakeUpsDriveAWholeChainWithoutWaitingForTheIntervalPass() {
        long projectId = projects.create(
                new CreateProjectRequest("vennela", "wake-" + UUID.randomUUID(), null)).id();
        long runId = runs.trigger(projectId, new TriggerRunRequest("abcdef1", "main", """
                jobs:
                  a: { image: alpine, steps: [ { run: echo a } ] }
                  b: { image: alpine, needs: a, steps: [ { run: echo b } ] }
                  c: { image: alpine, needs: b, steps: [ { run: echo c } ] }
                  d: { image: alpine, needs: c, steps: [ { run: echo d } ] }
                """)).id();

        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(50))
                .until(() -> runs.get(runId).status() == RunStatus.SUCCEEDED);
        assertThat(runs.get(runId).jobs()).hasSize(4);
    }
}
