package com.conveyorci.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.conveyorci.TestcontainersConfig;
import com.conveyorci.service.RunService;
import com.conveyorci.web.ApiModels.TriggerRunRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ApiIntegrationTest {

    private static final String PIPELINE = """
            name: ci
            jobs:
              build:
                image: maven:3.9-eclipse-temurin-21
                steps:
                  - name: Compile
                    run: mvn -B compile
              test:
                image: maven:3.9-eclipse-temurin-21
                needs: build
                retries: 1
                steps:
                  - run: mvn -B test
                  - run: echo done
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RunService runService;

    private long createProject() throws Exception {
        String body = json.writeValueAsString(Map.of("owner", "vennela", "name", "app-" + UUID.randomUUID()));
        String response = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.defaultBranch").value("main"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asLong();
    }

    private String runBody(String yaml) throws Exception {
        return json.writeValueAsString(Map.of("commitSha", "A1B2C3D4E5F6", "branch", "main", "pipelineYaml", yaml));
    }

    @Test
    void triggeringARunPersistsTheJobGraph() throws Exception {
        long projectId = createProject();

        String response = mvc.perform(post("/api/projects/{id}/runs", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(runBody(PIPELINE)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/runs/")))
                .andExpect(jsonPath("$.runNumber").value(1))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.commitSha").value("a1b2c3d4e5f6"))
                .andExpect(jsonPath("$.stages[0]", contains("build")))
                .andExpect(jsonPath("$.stages[1]", contains("test")))
                .andExpect(jsonPath("$.jobs[0].status").value("QUEUED"))
                .andExpect(jsonPath("$.jobs[1].status").value("PENDING"))
                .andExpect(jsonPath("$.jobs[1].needs", contains("build")))
                .andExpect(jsonPath("$.jobs[1].maxAttempts").value(2))
                .andExpect(jsonPath("$.jobs[1].steps.length()").value(2))
                .andExpect(jsonPath("$.jobs[1].steps[0].name").value("Step 1"))
                .andReturn().getResponse().getContentAsString();

        long runId = json.readTree(response).get("id").asLong();

        // Read back through a fresh transaction to prove it was persisted, not just echoed.
        mvc.perform(get("/api/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].steps[0].command").value("mvn -B compile"))
                .andExpect(jsonPath("$.jobs[1].needs", contains("build")));

        mvc.perform(post("/api/projects/{id}/runs", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(runBody(PIPELINE)))
                .andExpect(jsonPath("$.runNumber").value(2));

        mvc.perform(get("/api/projects/{id}/runs", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].runNumber").value(2))
                .andExpect(jsonPath("$[1].runNumber").value(1));
    }

    @Test
    void concurrentTriggersGetUniqueSequentialRunNumbers() throws Exception {
        long projectId = createProject();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> runService.trigger(projectId,
                        new TriggerRunRequest("abcdef1", "main", PIPELINE)).runNumber());
            }
            List<Integer> numbers = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(tasks)) {
                numbers.add(f.get());
            }
            Collections.sort(numbers);
            assertThat(numbers).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidPipelineReturns422WithAllErrors() throws Exception {
        long projectId = createProject();
        String cyclic = """
                jobs:
                  a: { image: x, needs: b, steps: [ { run: a } ] }
                  b: { image: x, needs: a, steps: [ { run: b } ] }
                """;
        mvc.perform(post("/api/projects/{id}/runs", projectId)
                        .contentType(MediaType.APPLICATION_JSON).content(runBody(cyclic)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Invalid pipeline"))
                .andExpect(jsonPath("$.errors", contains("dependency cycle detected: a -> b -> a")));

        mvc.perform(get("/api/projects/{id}/runs", projectId))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void requestValidationAndMissingResources() throws Exception {
        long projectId = createProject();
        String badSha = json.writeValueAsString(Map.of("commitSha", "not-a-sha", "branch", "main", "pipelineYaml", PIPELINE));
        mvc.perform(post("/api/projects/{id}/runs", projectId).contentType(MediaType.APPLICATION_JSON).content(badSha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(startsWith("commitSha:"))));

        mvc.perform(post("/api/projects/{id}/runs", 999_999).contentType(MediaType.APPLICATION_JSON).content(runBody(PIPELINE)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/runs/{id}", 999_999)).andExpect(status().isNotFound());
    }

    @Test
    void duplicateProjectReturns409() throws Exception {
        String body = json.writeValueAsString(Map.of("owner", "vennela", "name", "dup-" + UUID.randomUUID()));
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void validateEndpointReportsStagesOrErrors() throws Exception {
        String ok = mvc.perform(post("/api/pipelines/validate").contentType("application/yaml").content(PIPELINE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(ok);
        assertThat(node.get("valid").asBoolean()).isTrue();
        assertThat(node.get("stages").toString()).isEqualTo("[[\"build\"],[\"test\"]]");

        mvc.perform(post("/api/pipelines/validate").contentType(MediaType.TEXT_PLAIN).content("jobs: {}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors.length()").value(1));
    }
}
