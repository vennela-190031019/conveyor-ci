package com.conveyorci.logs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.conveyorci.TestcontainersConfig;
import com.conveyorci.domain.Statuses.JobStatus;
import com.conveyorci.domain.Statuses.RunStatus;
import com.conveyorci.logs.LiveLogService.EndPayload;
import com.conveyorci.logs.LiveLogService.LinesPayload;
import com.conveyorci.logs.LogEvents.LogLine;
import com.conveyorci.service.NotFoundException;
import com.conveyorci.service.ProjectService;
import com.conveyorci.service.RunService;
import com.conveyorci.web.ApiModels.CreateProjectRequest;
import com.conveyorci.web.ApiModels.RunResponse;
import com.conveyorci.web.ApiModels.TriggerRunRequest;

/** Live logs end to end: real worker → Redis list + pub/sub → LiveLogService → viewer. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class LiveLogIntegrationTest {

    @Autowired RunService runs;
    @Autowired ProjectService projects;
    @Autowired LiveLogService liveLogs;
    @Autowired MockMvc mvc;

    /** Captures what a browser would receive. */
    static final class RecordingSink implements LiveLogService.Sink {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<Object> payloads = new CopyOnWriteArrayList<>();
        volatile boolean completed;

        @Override
        public void send(String event, Object data) {
            events.add(event);
            payloads.add(data);
        }

        @Override
        public void keepAlive() {
        }

        @Override
        public void complete() {
            completed = true;
        }

        List<LogLine> lines() {
            List<LogLine> all = new ArrayList<>();
            payloads.stream().filter(LinesPayload.class::isInstance).map(LinesPayload.class::cast)
                    .forEach(p -> all.addAll(p.lines()));
            return all;
        }

        String endStatus() {
            return payloads.stream().filter(EndPayload.class::isInstance).map(p -> ((EndPayload) p).status())
                    .findFirst().orElse(null);
        }
    }

    private RunResponse trigger(String yaml) {
        long projectId = projects.create(new CreateProjectRequest("vennela", "logs-" + UUID.randomUUID(), null)).id();
        return runs.trigger(projectId, new TriggerRunRequest("abcdef1", "main", yaml));
    }

    private static void assertContiguous(List<LogLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            assertThat(lines.get(i).seq()).as("seq at index " + i).isEqualTo(i + 1);
        }
    }

    @Test
    void viewerJoiningMidRunGetsEveryLineExactlyOnceInOrder() {
        RunResponse run = trigger("""
                jobs:
                  build: { image: alpine, steps: [ { name: Ticks, run: tick 25 } ] }
                """);
        long jobId = run.jobs().get(0).id();

        // Join while the job is printing, so the viewer needs both the snapshot and live events.
        await().atMost(Duration.ofSeconds(10))
                .until(() -> runs.get(run.id()).jobs().get(0).status() == JobStatus.RUNNING);
        RecordingSink sink = new RecordingSink();
        liveLogs.subscribe(jobId, sink);

        await().atMost(Duration.ofSeconds(15)).until(() -> sink.completed);
        List<LogLine> lines = sink.lines();
        assertContiguous(lines);
        assertThat(lines.get(0).text()).isEqualTo("$ tick 25");
        assertThat(lines).extracting(LogLine::text).contains("tick 1", "tick 25").hasSize(26);
        assertThat(lines).allSatisfy(l -> assertThat(l.step()).isEqualTo(1));
        assertThat(sink.endStatus()).isEqualTo("SUCCEEDED");
        assertThat(liveLogs.viewerCount(jobId)).isZero();
    }

    @Test
    void viewerOfAFinishedJobGetsTheBufferAndEnd() {
        RunResponse run = trigger("""
                jobs:
                  build: { image: alpine, steps: [ { run: lines 3 }, { run: lines 2 } ] }
                """);
        await().atMost(Duration.ofSeconds(10)).until(() -> runs.get(run.id()).status() == RunStatus.SUCCEEDED);
        long jobId = run.jobs().get(0).id();

        RecordingSink sink = new RecordingSink();
        liveLogs.subscribe(jobId, sink);

        assertThat(sink.completed).isTrue();
        List<LogLine> lines = sink.lines();
        assertContiguous(lines);
        assertThat(lines).extracting(LogLine::step).containsExactly(1, 1, 1, 1, 2, 2, 2);
        assertThat(lines).extracting(LogLine::text)
                .containsExactly("$ lines 3", "line 1", "line 2", "line 3", "$ lines 2", "line 1", "line 2");
        assertThat(sink.endStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void aRetryReplacesThePreviousAttemptsOutput() {
        String flaky = "flaky-" + UUID.randomUUID();
        RunResponse run = trigger("""
                jobs:
                  test: { image: alpine, retries: 1, steps: [ { run: "%s" } ] }
                """.formatted(flaky));
        await().atMost(Duration.ofSeconds(10)).until(() -> runs.get(run.id()).status() == RunStatus.SUCCEEDED);

        RecordingSink sink = new RecordingSink();
        liveLogs.subscribe(run.jobs().get(0).id(), sink);

        assertThat(sink.payloads).filteredOn(LinesPayload.class::isInstance)
                .allSatisfy(p -> assertThat(((LinesPayload) p).attempt()).isEqualTo(2));
        assertThat(sink.lines()).extracting(LogLine::text).contains("flaky run #2").doesNotContain("flaky run #1");
    }

    @Test
    void streamsOverServerSentEvents() throws Exception {
        RunResponse run = trigger("""
                jobs:
                  build: { image: alpine, steps: [ { run: lines 3 } ] }
                """);
        await().atMost(Duration.ofSeconds(10)).until(() -> runs.get(run.id()).status() == RunStatus.SUCCEEDED);

        MvcResult result = mvc.perform(get("/api/jobs/{id}/logs/stream", run.jobs().get(0).id()))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(5000);
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
        assertThat(body).contains("event:lines").contains("line 3").contains("event:end").contains("SUCCEEDED");
    }

    @Test
    void unknownJobIsRejected() {
        assertThatThrownBy(() -> liveLogs.subscribe(987_654_321L, new RecordingSink()))
                .isInstanceOf(NotFoundException.class);
    }
}
