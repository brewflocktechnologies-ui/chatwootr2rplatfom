package com.acme.support.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;

/**
 * Chatwoot AgentBot signature: X-Chatwoot-Signature = "sha256=" + hex(HMAC-SHA256(secret, timestamp + "." + rawBody)).
 * Verified against a real captured delivery (see README-backend.md). Operates on raw bytes, never re-serialised JSON.
 */
public final class AgentBotSignatureVerifier {

    public static final Duration MAX_SKEW = Duration.ofMinutes(5);

    public enum Result { VALID, MISSING_HEADERS, STALE_TIMESTAMP, INVALID_SIGNATURE }

    private AgentBotSignatureVerifier() {
    }

    public static Result verify(String secret, String timestampHeader, String signatureHeader, byte[] rawBody, Instant now) {
        if (secret == null || secret.isEmpty() || timestampHeader == null || signatureHeader == null || rawBody == null) {
            return Result.MISSING_HEADERS;
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return Result.MISSING_HEADERS;
        }
        if (Math.abs(now.getEpochSecond() - ts) > MAX_SKEW.getSeconds()) {
            return Result.STALE_TIMESTAMP;
        }
        String expected = "sha256=" + HexFormat.of().formatHex(hmac(secret, timestampHeader.trim(), rawBody));
        boolean ok = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                signatureHeader.trim().getBytes(StandardCharsets.US_ASCII));
        return ok ? Result.VALID : Result.INVALID_SIGNATURE;
    }

    static byte[] hmac(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            mac.update(body);
            return mac.doFinal();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
