package com.acme.support.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AgentBotSignatureVerifierTest {

    static final String SECRET = "test-bot-secret-0123456789abcdef";
    static final Instant NOW = Instant.ofEpochSecond(1_795_500_300L);

    @Test
    void acceptsValidSignatureOverRawBytes() throws IOException {
        byte[] body = fixture("fixtures/agent-bot-message-created-incoming.json");
        String ts = "1795500226";
        assertThat(AgentBotSignatureVerifier.verify(SECRET, ts, sign(SECRET, ts, body), body, NOW))
                .isEqualTo(AgentBotSignatureVerifier.Result.VALID);
    }

    @Test
    void rejectsTamperedBody() throws IOException {
        byte[] body = fixture("fixtures/agent-bot-message-created-incoming.json");
        String ts = "1795500226";
        String sig = sign(SECRET, ts, body);
        byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("standard delivery", "express delivery")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(AgentBotSignatureVerifier.verify(SECRET, ts, sig, tampered, NOW))
                .isEqualTo(AgentBotSignatureVerifier.Result.INVALID_SIGNATURE);
    }

    @Test
    void rejectsWrongSecret() throws IOException {
        byte[] body = fixture("fixtures/agent-bot-message-created-incoming.json");
        String ts = "1795500226";
        assertThat(AgentBotSignatureVerifier.verify("another-secret-value-000000000000", ts, sign(SECRET, ts, body), body, NOW))
                .isEqualTo(AgentBotSignatureVerifier.Result.INVALID_SIGNATURE);
    }

    @Test
    void rejectsStaleTimestamp() throws IOException {
        byte[] body = fixture("fixtures/agent-bot-message-created-incoming.json");
        String ts = String.valueOf(NOW.getEpochSecond() - 301);
        assertThat(AgentBotSignatureVerifier.verify(SECRET, ts, sign(SECRET, ts, body), body, NOW))
                .isEqualTo(AgentBotSignatureVerifier.Result.STALE_TIMESTAMP);
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertThat(AgentBotSignatureVerifier.verify(SECRET, null, "sha256=00", body, NOW))
                .isEqualTo(AgentBotSignatureVerifier.Result.MISSING_HEADERS);
        assertThat(AgentBotSignatureVerifier.verify(SECRET, "abc", "sha256=00", body, NOW))
                .isEqualTo(AgentBotSignatureVerifier.Result.MISSING_HEADERS);
        assertThat(AgentBotSignatureVerifier.verify("", "1795500300", "sha256=00", body, NOW))
                .isEqualTo(AgentBotSignatureVerifier.Result.MISSING_HEADERS);
    }

    static String sign(String secret, String ts, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + ".").getBytes(StandardCharsets.US_ASCII));
            mac.update(body);
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] fixture(String path) throws IOException {
        try (InputStream in = AgentBotSignatureVerifierTest.class.getClassLoader().getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }
}
