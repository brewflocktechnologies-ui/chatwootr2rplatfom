package com.acme.support.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.support.AbstractIntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class WebhookIntegrationTest extends AbstractIntegrationTest {

    private static final String PATH = "/api/webhooks/chatwoot/agent-bot";

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void reset() {
        resetPlatformState();
    }

    @Test
    void validSignedCustomerMessageIsQueuedOnceAndDuplicateIsAcknowledgedWithoutSecondRow() throws Exception {
        byte[] body = fixture("fixtures/agent-bot-message-created-incoming.json");
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String sig = AgentBotSignatureVerifierTest.sign(BOT_SECRET, ts, body);

        mvc.perform(post(PATH).contentType("application/json").content(body)
                        .header("X-Chatwoot-Delivery", newDelivery())
                        .header("X-Chatwoot-Timestamp", ts).header("X-Chatwoot-Signature", sig))
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("accepted"));

        mvc.perform(post(PATH).contentType("application/json").content(body)
                        .header("X-Chatwoot-Delivery", newDelivery())
                        .header("X-Chatwoot-Timestamp", ts).header("X-Chatwoot-Signature", sig))
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("duplicate"));

        Long rows = jdbc.sql("select count(*) from webhook_inbox where dedupe_key = 'chatwoot:4:message_created:7'")
                .query(Long.class).single();
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void badSignatureIsRejectedAndNothingIsStored() throws Exception {
        byte[] body = fixture("fixtures/agent-bot-message-created-incoming.json");
        String ts = String.valueOf(Instant.now().getEpochSecond());
        mvc.perform(post(PATH).contentType("application/json").content(body)
                        .header("X-Chatwoot-Delivery", newDelivery())
                        .header("X-Chatwoot-Timestamp", ts)
                        .header("X-Chatwoot-Signature", "sha256=" + "0".repeat(64)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("select count(*) from webhook_inbox").query(Long.class).single()).isZero();
    }

    @Test
    void staleTimestampIsRejected() throws Exception {
        byte[] body = fixture("fixtures/agent-bot-message-created-incoming.json");
        String ts = String.valueOf(Instant.now().getEpochSecond() - 400);
        String sig = AgentBotSignatureVerifierTest.sign(BOT_SECRET, ts, body);
        mvc.perform(post(PATH).contentType("application/json").content(body)
                        .header("X-Chatwoot-Delivery", newDelivery())
                        .header("X-Chatwoot-Timestamp", ts).header("X-Chatwoot-Signature", sig))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void botReplyDeliveryIsAcknowledgedAndIgnored() throws Exception {
        byte[] body = fixture("fixtures/agent-bot-message-created-outgoing-bot.json");
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String sig = AgentBotSignatureVerifierTest.sign(BOT_SECRET, ts, body);
        mvc.perform(post(PATH).contentType("application/json").content(body)
                        .header("X-Chatwoot-Delivery", newDelivery())
                        .header("X-Chatwoot-Timestamp", ts).header("X-Chatwoot-Signature", sig))
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("ignored"));
        assertThat(jdbc.sql("select count(*) from webhook_inbox").query(Long.class).single()).isZero();
    }

    @Test
    void adminApiRequiresKeyAndPublicConfigStaysSecretFree() throws Exception {
        mvc.perform(get("/api/admin/tenant")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/tenant").header("X-Admin-Api-Key", "wrong-key")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/tenant").header("X-Admin-Api-Key", ADMIN_KEY)).andExpect(status().isOk());
        mvc.perform(get("/api/public/widget-config"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.websiteToken")
                        .value("test-website-token-not-real"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void widgetConfigUsesBrowserReachableChatwootUrl() throws Exception {
        mvc.perform(get("/api/public/widget-config"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.chatwootBaseUrl")
                        .value("http://localhost:3100"));
    }

    private static byte[] fixture(String path) throws IOException {
        try (InputStream in = WebhookIntegrationTest.class.getClassLoader().getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }
}
