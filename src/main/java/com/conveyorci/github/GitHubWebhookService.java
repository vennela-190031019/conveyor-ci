package com.conveyorci.github;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.conveyorci.domain.PipelineRun;
import com.conveyorci.domain.Project;
import com.conveyorci.domain.ProjectRepository;
import com.conveyorci.service.RunService;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Turns a GitHub push into a run:
 * <ol>
 *   <li>ignore redeliveries (by delivery id), tag pushes and branch deletions</li>
 *   <li>find the Conveyor project registered for the repository</li>
 *   <li>fetch {@code .conveyor.yml} at the pushed commit</li>
 *   <li>in one transaction: record the delivery and create the run</li>
 * </ol>
 * The GitHub fetch happens <em>before</em> the transaction, so no database locks are held
 * while waiting on the network.
 */
@Service
public class GitHubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookService.class);

    public enum Outcome { CREATED, DUPLICATE, IGNORED }

    public record WebhookResult(Outcome outcome, String detail, Long runId) {
    }

    private final WebhookDeliveryStore deliveries;
    private final ProjectRepository projects;
    private final GitHubClient github;
    private final RunService runService;
    private final TransactionTemplate transaction;
    private final String pipelinePath;

    public GitHubWebhookService(WebhookDeliveryStore deliveries, ProjectRepository projects, GitHubClient github,
                                RunService runService, TransactionTemplate transaction,
                                @Value("${conveyor.github.pipeline-path:.conveyor.yml}") String pipelinePath) {
        this.deliveries = deliveries;
        this.projects = projects;
        this.github = github;
        this.runService = runService;
        this.transaction = transaction;
        this.pipelinePath = pipelinePath;
    }

    public WebhookResult handlePush(String deliveryId, JsonNode payload) throws IOException, InterruptedException {
        if (deliveries.exists(deliveryId)) {
            return new WebhookResult(Outcome.DUPLICATE, "delivery " + deliveryId + " was already processed", null);
        }

        String ref = payload.path("ref").asText("");
        String sha = payload.path("after").asText("");
        if (payload.path("deleted").asBoolean(false) || !ref.startsWith("refs/heads/")
                || !sha.matches("[0-9a-f]{40}") || sha.matches("0{40}")) {
            return ignore(deliveryId, "not a push to a branch (" + ref + ")");
        }
        String branch = ref.substring("refs/heads/".length());

        JsonNode repository = payload.path("repository");
        String owner = repository.path("owner").path("login").asText(repository.path("owner").path("name").asText(""));
        String repo = repository.path("name").asText("");
        Optional<Project> project = projects.findFirstByOwnerIgnoreCaseAndNameIgnoreCaseOrderByIdAsc(owner, repo);
        if (project.isEmpty()) {
            return ignore(deliveryId, "no Conveyor project registered for " + owner + "/" + repo);
        }

        Optional<String> pipeline = github.fetchFile(owner, repo, pipelinePath, sha);
        if (pipeline.isEmpty()) {
            return ignore(deliveryId, "no " + pipelinePath + " in " + owner + "/" + repo + "@" + sha.substring(0, 7));
        }

        WebhookResult result = transaction.execute(status -> {
            PipelineRun run = runService.triggerFromPush(project.get().getId(), sha, branch, pipeline.get());
            if (!deliveries.record(deliveryId, "push", "created run " + run.getId(), run.getId())) {
                // A concurrent redelivery won the race: undo this run.
                status.setRollbackOnly();
                return new WebhookResult(Outcome.DUPLICATE, "delivery " + deliveryId + " was already processed", null);
            }
            return new WebhookResult(Outcome.CREATED,
                    "run #" + run.getRunNumber() + " for " + owner + "/" + repo + "@" + branch, run.getId());
        });
        log.info("push to {}/{}@{} ({}): {}", owner, repo, branch, sha.substring(0, 7), result.detail());
        return result;
    }

    public WebhookResult ignore(String deliveryId, String reason) {
        deliveries.record(deliveryId, "push", "ignored: " + reason, null);
        log.info("ignored delivery {}: {}", deliveryId, reason);
        return new WebhookResult(Outcome.IGNORED, reason, null);
    }
}
