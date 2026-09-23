package com.conveyorci.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.conveyorci.TestcontainersConfig;
import com.conveyorci.domain.Statuses.JobStatus;
import com.conveyorci.domain.Statuses.RunSource;
import com.conveyorci.domain.Statuses.RunStatus;
import com.conveyorci.engine.FakeContainerRuntime;
import com.conveyorci.service.ProjectService;
import com.conveyorci.service.RunService;
import com.conveyorci.web.ApiModels.CreateProjectRequest;
import com.conveyorci.web.ApiModels.RunResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Push-to-status, end to end: signed webhook, pipeline fetched from (fake) GitHub, code checked out
 * into the job, run executed by the real scheduler and worker, and the result posted back as a
 * commit status.
 */
@SpringBootTest(properties = {
        "conveyor.github.webhook-secret=" + GitHubIntegrationTest.SECRET,
        "conveyor.github.status-interval-ms=100",
        "conveyor.github.status-retry-base-ms=50",
        "conveyor.public-url=http://ci.example"})
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class GitHubIntegrationTest {

    static final String SECRET = "test-webhook-secret";
    private static final String OWNER = "octo";
    private static final FakeGitHub GITHUB;

    static {
        try {
            GITHUB = new FakeGitHub();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void githubUrl(DynamicPropertyRegistry registry) {
        registry.add("conveyor.github.api-url", GITHUB::url);
    }

    @AfterAll
    static void stopGitHub() {
        GITHUB.close();
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProjectService projects;
    @Autowired RunService runs;
    @Autowired FakeContainerRuntime runtime;

    private String newRepo() {
        String name = "app-" + UUID.randomUUID().toString().substring(0, 8);
        projects.create(new CreateProjectRequest(OWNER, name, null));
        return name;
    }

    private static String randomSha() {
        StringBuilder sha = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sha.append("0123456789abcdef".charAt(ThreadLocalRandom.current().nextInt(16)));
        }
        return sha.toString();
    }

    private ResultActions deliver(String event, String deliveryId, byte[] body, String signature) throws Exception {
        return mvc.perform(post("/api/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-GitHub-Event", event)
                .header("X-GitHub-Delivery", deliveryId)
                .header("X-Hub-Signature-256", signature)
                .content(body));
    }

    private ResultActions push(String deliveryId, String repo, String ref, String sha, boolean deleted)
            throws Exception {
        byte[] body = json.writeValueAsBytes(Map.of(
                "ref", ref,
                "after", sha,
                "deleted", deleted,
                "repository", Map.of("name", repo, "owner", Map.of("login", OWNER, "name", OWNER))));
        return deliver("push", deliveryId, body,
                WebhookSignatureVerifier.sign(SECRET.getBytes(StandardCharsets.UTF_8), body));
    }

    private ResultActions push(String repo, String sha) throws Exception {
        return push(UUID.randomUUID().toString(), repo, "refs/heads/main", sha, false);
    }

    private long runIdFrom(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("runId").asLong();
    }

    private RunResponse awaitRun(long runId, RunStatus expected) {
        await().atMost(Duration.ofSeconds(20)).until(() -> runs.get(runId).status() == expected);
        return runs.get(runId);
    }

    private void awaitLastState(String sha, String state) {
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            List<String> states = GITHUB.statesFor(sha);
            return !states.isEmpty() && states.get(states.size() - 1).equals(state);
        });
    }

    @Test
    void pushRunsThePipelineOnCheckedOutCodeAndReportsSuccess() throws Exception {
        String repo = newRepo();
        String sha = randomSha();
        GITHUB.putFile(OWNER + "/" + repo, sha, ".conveyor.yml", """
                name: ci
                jobs:
                  build: { image: alpine, steps: [ { run: cat README.md } ] }
                """);
        GITHUB.putRepository(OWNER + "/" + repo, sha, Map.of("README.md", "hello", "src/Main.java", "class Main {}"));

        long runId = runIdFrom(push(repo, sha)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("CREATED")));

        RunResponse run = awaitRun(runId, RunStatus.SUCCEEDED);
        assertThat(run.source()).isEqualTo(RunSource.GITHUB_PUSH);
        assertThat(run.checkout()).isTrue();
        assertThat(run.commitSha()).isEqualTo(sha);
        assertThat(run.branch()).isEqualTo("main");

        long jobId = run.jobs().get(0).id();
        assertThat(runtime.copiedFiles("conveyor-job-" + jobId + "-1")).containsExactly("README.md", "src/Main.java");
        assertThat(runs.logs(jobId)).contains("$ checkout " + OWNER + "/" + repo + "@" + sha);

        awaitLastState(sha, "success");
        assertThat(GITHUB.statesFor(sha)).doesNotContain("failure", "error");
        assertThat(GITHUB.statuses()).filteredOn(s -> s.sha().equals(sha)).allSatisfy(s -> {
            assertThat(s.repo()).isEqualTo(OWNER + "/" + repo);
            assertThat(s.body()).contains("\"context\":\"conveyor-ci\"")
                    .contains("\"target_url\":\"http://ci.example/api/runs/" + runId + "\"");
        });
    }

    @Test
    void runningRunIsPendingThenCancelledRunReportsError() throws Exception {
        String repo = newRepo();
        String sha = randomSha();
        GITHUB.putFile(OWNER + "/" + repo, sha, ".conveyor.yml", """
                jobs:
                  slow: { image: alpine, steps: [ { run: hang } ] }
                """);
        GITHUB.putRepository(OWNER + "/" + repo, sha, Map.of("README.md", "hi"));

        long runId = runIdFrom(push(repo, sha).andExpect(status().isCreated()));
        awaitLastState(sha, "pending");

        runs.cancel(runId);
        awaitLastState(sha, "error");
        assertThat(GITHUB.statuses()).filteredOn(s -> s.sha().equals(sha))
                .last().satisfies(s -> assertThat(s.body()).contains("was cancelled"));
    }

    @Test
    void invalidPipelineFileProducesAFailedRunAndAFailureStatus() throws Exception {
        String repo = newRepo();
        String sha = randomSha();
        GITHUB.putFile(OWNER + "/" + repo, sha, ".conveyor.yml", "jobs: {}");

        long runId = runIdFrom(push(repo, sha).andExpect(status().isCreated()));

        RunResponse run = runs.get(runId);
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.jobs()).isEmpty();
        assertThat(run.failureReason()).startsWith("invalid pipeline:").contains("non-empty mapping");

        awaitLastState(sha, "failure");
        assertThat(GITHUB.statuses()).filteredOn(s -> s.sha().equals(sha))
                .last().satisfies(s -> assertThat(s.body()).contains("invalid pipeline"));
    }

    @Test
    void failedCheckoutFailsTheJob() throws Exception {
        String repo = newRepo();
        String sha = randomSha();
        GITHUB.putFile(OWNER + "/" + repo, sha, ".conveyor.yml", """
                jobs:
                  build: { image: alpine, steps: [ { run: make } ] }
                """);
        // No tarball registered, so the download returns 404.

        long runId = runIdFrom(push(repo, sha).andExpect(status().isCreated()));

        RunResponse run = awaitRun(runId, RunStatus.FAILED);
        assertThat(run.jobs().get(0).status()).isEqualTo(JobStatus.FAILED);
        assertThat(run.jobs().get(0).failureReason()).contains("checkout of " + OWNER + "/" + repo).contains("404");
        awaitLastState(sha, "failure");
    }

    @Test
    void statusDeliveryIsRetriedWhenGitHubIsFailing() throws Exception {
        String repo = newRepo();
        String sha = randomSha();
        GITHUB.putFile(OWNER + "/" + repo, sha, ".conveyor.yml", "jobs: {}");
        GITHUB.failNextStatusPosts(2);

        push(repo, sha).andExpect(status().isCreated());

        awaitLastState(sha, "failure");
    }

    @Test
    void redeliveredWebhookDoesNotStartASecondRun() throws Exception {
        String repo = newRepo();
        String sha = randomSha();
        GITHUB.putFile(OWNER + "/" + repo, sha, ".conveyor.yml", """
                jobs:
                  build: { image: alpine, steps: [ { run: make } ] }
                """);
        GITHUB.putRepository(OWNER + "/" + repo, sha, Map.of("README.md", "hi"));
        String deliveryId = UUID.randomUUID().toString();

        push(deliveryId, repo, "refs/heads/main", sha, false).andExpect(status().isCreated());
        push(deliveryId, repo, "refs/heads/main", sha, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        long projectId = projects.list().stream().filter(p -> p.name().equals(repo)).findFirst().orElseThrow().id();
        assertThat(runs.listForProject(projectId)).hasSize(1);
    }

    @Test
    void rejectsBadSignatures() throws Exception {
        String repo = newRepo();
        byte[] body = json.writeValueAsBytes(Map.of("ref", "refs/heads/main", "after", randomSha(),
                "repository", Map.of("name", repo, "owner", Map.of("login", OWNER))));

        deliver("push", UUID.randomUUID().toString(), body,
                WebhookSignatureVerifier.sign("wrong-secret".getBytes(StandardCharsets.UTF_8), body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/webhooks/github").contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Event", "push").header("X-GitHub-Delivery", "no-signature").content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ignoresPushesThatShouldNotBuild() throws Exception {
        String repo = newRepo();
        String sha = randomSha();

        push(repo, sha).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("IGNORED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no .conveyor.yml")));
        push(UUID.randomUUID().toString(), repo, "refs/tags/v1.0", sha, false)
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
        push(UUID.randomUUID().toString(), repo, "refs/heads/old-branch", "0".repeat(40), true)
                .andExpect(jsonPath("$.outcome").value("IGNORED"));
        push(UUID.randomUUID().toString(), "not-registered-" + UUID.randomUUID(), "refs/heads/main", sha, false)
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no Conveyor project")));
    }

    @Test
    void answersPing() throws Exception {
        byte[] body = "{\"zen\":\"Keep it logically awesome.\"}".getBytes(StandardCharsets.UTF_8);
        deliver("ping", UUID.randomUUID().toString(), body,
                WebhookSignatureVerifier.sign(SECRET.getBytes(StandardCharsets.UTF_8), body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail").value("pong"));
    }
}
