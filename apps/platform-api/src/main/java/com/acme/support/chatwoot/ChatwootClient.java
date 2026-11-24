package com.acme.support.chatwoot;

import com.acme.support.shared.Http;
import com.acme.support.shared.UpstreamException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * Chatwoot REST adapter. Admin token: reads. Bot token: bot replies, private notes, status changes.
 * Verified shapes: see README-backend.md section "Verified Chatwoot contracts".
 */
@Component
public class ChatwootClient {

    private static final String UPSTREAM = "chatwoot";

    private final ChatwootProperties props;
    private final RestClient http;

    public ChatwootClient(ChatwootProperties props) {
        this.props = props;
        this.http = RestClient.builder()
                .baseUrl(props.baseUrl() + "/api/v1/accounts/" + props.accountId())
                .requestFactory(Http.factory(props.connectTimeout(), props.readTimeout()))
                .build();
    }

    public long inboxId() {
        return props.inboxId();
    }

    public long agentBotId() {
        return props.agentBotId();
    }

    public long accountId() {
        return props.accountId();
    }

    public ConversationSnapshot getConversation(long conversationId) {
        JsonNode n = call("getConversation", () -> http.get()
                .uri("/conversations/{id}", conversationId)
                .header("api_access_token", props.apiToken())
                .retrieve()
                .body(JsonNode.class));
        Long assignee = null;
        if (n.path("meta").path("assignee").isObject() && n.path("meta").path("assignee").hasNonNull("id")) {
            assignee = n.path("meta").path("assignee").get("id").asLong();
        } else if (n.hasNonNull("assignee_id")) {
            assignee = n.get("assignee_id").asLong();
        }
        return new ConversationSnapshot(conversationId, n.path("status").asString(""), assignee);
    }

    /** Creates a customer-visible (privateFlag=false) or internal (true) outgoing message. Returns Chatwoot message id. */
    public long postMessage(long conversationId, String content, boolean privateFlag) {
        JsonNode n = call("postMessage", () -> http.post()
                .uri("/conversations/{id}/messages", conversationId)
                .header("api_access_token", props.botAccessToken())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("content", content, "message_type", "outgoing", "private", privateFlag))
                .retrieve()
                .body(JsonNode.class));
        if (!n.hasNonNull("id")) {
            throw new UpstreamException(UPSTREAM, 200, false, "postMessage returned no id", null);
        }
        return n.get("id").asLong();
    }

    public void toggleStatus(long conversationId, String status) {
        call("toggleStatus", () -> http.post()
                .uri("/conversations/{id}/toggle_status", conversationId)
                .header("api_access_token", props.botAccessToken())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of("status", status))
                .retrieve()
                .body(JsonNode.class));
    }

    /** Looks for an earlier private note carrying this marker, so a retry after a timeout never duplicates it. */
    public Optional<Long> findPrivateNoteWithMarker(long conversationId, String marker) {
        JsonNode n = call("listMessages", () -> http.get()
                .uri("/conversations/{id}/messages", conversationId)
                .header("api_access_token", props.apiToken())
                .retrieve()
                .body(JsonNode.class));
        for (JsonNode m : n.path("payload")) {
            if (m.path("private").asBoolean(false) && m.path("content").asString("").contains(marker)) {
                return Optional.of(m.get("id").asLong());
            }
        }
        return Optional.empty();
    }

    /**
     * True when a public, human-authored outgoing message (sender type "user") newer than the customer message exists.
     * Live shape (admin list): message_type 1 (numeric), private false, sender.type "user", ids increase.
     */
    public boolean hasHumanReplyAfter(long conversationId, long customerMessageId) {
        JsonNode n = call("listMessages", () -> http.get()
                .uri("/conversations/{id}/messages", conversationId)
                .header("api_access_token", props.apiToken())
                .retrieve()
                .body(JsonNode.class));
        for (JsonNode m : n.path("payload")) {
            boolean outgoing = m.path("message_type").asInt(-1) == 1
                    || "outgoing".equals(m.path("message_type").asString(""));
            boolean human = "user".equals(m.path("sender").path("type").asString(""));
            if (outgoing && human && !m.path("private").asBoolean(false)
                    && m.path("id").asLong(0) > customerMessageId) {
                return true;
            }
        }
        return false;
    }

    /** Liveness probe for diagnostics. Admin token; no data is returned to callers. */
    public boolean ping() {
        try {
            call("ping", () -> http.get().header("api_access_token", props.apiToken()).retrieve().body(JsonNode.class));
            return true;
        } catch (UpstreamException e) {
            return false;
        }
    }

    private <T> T call(String op, Supplier<T> s) {
        try {
            return s.get();
        } catch (RestClientResponseException e) {
            int st = e.getStatusCode().value();
            throw new UpstreamException(UPSTREAM, st, st >= 500 || st == 429,
                    "chatwoot " + op + " failed with HTTP " + st, e);
        } catch (ResourceAccessException e) {
            throw new UpstreamException(UPSTREAM, 0, true, "chatwoot " + op + " transport error", e);
        }
    }

}
