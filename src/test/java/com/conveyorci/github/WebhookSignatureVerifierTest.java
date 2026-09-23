package com.conveyorci.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class WebhookSignatureVerifierTest {

    private static final byte[] BODY = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void matchesGitHubsDocumentedExample() {
        // Example from GitHub's "Validating webhook deliveries" docs.
        String signature = WebhookSignatureVerifier.sign("It's a Secret to Everybody".getBytes(StandardCharsets.UTF_8),
                "Hello, World!".getBytes(StandardCharsets.UTF_8));
        assertThat(signature)
                .isEqualTo("sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17");
    }

    @Test
    void acceptsCorrectSignature() {
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier("s3cret");
        String signature = WebhookSignatureVerifier.sign("s3cret".getBytes(StandardCharsets.UTF_8), BODY);
        assertThat(verifier.isValid(BODY, signature)).isTrue();
    }

    @Test
    void rejectsWrongSecretTamperedBodyAndMissingHeader() {
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier("s3cret");
        String wrongSecret = WebhookSignatureVerifier.sign("other".getBytes(StandardCharsets.UTF_8), BODY);
        String valid = WebhookSignatureVerifier.sign("s3cret".getBytes(StandardCharsets.UTF_8), BODY);

        assertThat(verifier.isValid(BODY, wrongSecret)).isFalse();
        assertThat(verifier.isValid("{\"ref\":\"refs/heads/evil\"}".getBytes(StandardCharsets.UTF_8), valid)).isFalse();
        assertThat(verifier.isValid(BODY, null)).isFalse();
        assertThat(verifier.isValid(BODY, valid.substring("sha256=".length()))).isFalse();
    }

    @Test
    void failsClosedWithoutASecret() {
        WebhookSignatureVerifier verifier = new WebhookSignatureVerifier("");
        assertThat(verifier.isConfigured()).isFalse();
        assertThat(verifier.isValid(BODY, WebhookSignatureVerifier.sign("any".getBytes(StandardCharsets.UTF_8), BODY)))
                .isFalse();
    }
}
