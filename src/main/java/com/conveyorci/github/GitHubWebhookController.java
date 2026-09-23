package com.conveyorci.github;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.conveyorci.github.GitHubWebhookService.Outcome;
import com.conveyorci.github.GitHubWebhookService.WebhookResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Receives GitHub webhooks. The body is read as raw bytes because the signature covers the exact
 * bytes GitHub sent; parsing and re-serializing JSON first would break verification.
 */
@RestController
public class GitHubWebhookController {

    private final WebhookSignatureVerifier verifier;
    private final GitHubWebhookService service;
    private final ObjectMapper json;

    public GitHubWebhookController(WebhookSignatureVerifier verifier, GitHubWebhookService service,
                                   ObjectMapper json) {
        this.verifier = verifier;
        this.service = service;
        this.json = json;
    }

    @PostMapping("/api/webhooks/github")
    public ResponseEntity<WebhookResult> receive(@RequestHeader("X-GitHub-Event") String event,
                                                 @RequestHeader("X-GitHub-Delivery") String deliveryId,
                                                 @RequestHeader(value = "X-Hub-Signature-256", required = false)
                                                 String signature,
                                                 @RequestBody byte[] body) throws IOException, InterruptedException {
        if (!verifier.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new WebhookResult(Outcome.IGNORED,
                    "webhooks are disabled: set conveyor.github.webhook-secret", null));
        }
        if (!verifier.isValid(body, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new WebhookResult(Outcome.IGNORED, "invalid signature", null));
        }
        return switch (event) {
            case "ping" -> ResponseEntity.ok(new WebhookResult(Outcome.IGNORED, "pong", null));
            case "push" -> {
                JsonNode payload = json.readTree(body);
                WebhookResult result = service.handlePush(deliveryId, payload);
                yield ResponseEntity.status(result.outcome() == Outcome.CREATED ? HttpStatus.CREATED : HttpStatus.OK)
                        .body(result);
            }
            default -> ResponseEntity.ok(new WebhookResult(Outcome.IGNORED, "unsupported event: " + event, null));
        };
    }
}
