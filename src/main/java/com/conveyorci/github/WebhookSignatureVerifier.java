package com.conveyorci.github;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies GitHub's {@code X-Hub-Signature-256} header: an HMAC-SHA256 of the raw request body,
 * keyed with the webhook secret. Without this, anyone who finds the URL could trigger builds.
 * The comparison is constant-time so the signature can't be guessed byte by byte from timing.
 */
@Component
public class WebhookSignatureVerifier {

    private static final String PREFIX = "sha256=";

    private final byte[] secret;

    public WebhookSignatureVerifier(@Value("${conveyor.github.webhook-secret:}") String secret) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isConfigured() {
        return secret.length > 0;
    }

    /** Fails closed: with no secret configured, nothing verifies. */
    public boolean isValid(byte[] body, String signatureHeader) {
        if (!isConfigured() || signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signatureHeader.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    /** The header value GitHub would send for {@code body}. */
    public static String sign(byte[] secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
