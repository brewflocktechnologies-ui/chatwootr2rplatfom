package com.acme.support.webhook;

import com.acme.support.chatwoot.ChatwootProperties;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AgentBot delivery endpoint. Verifies the signature over the raw bytes, dedupes durably, and returns 202 quickly.
 * The AI turn runs later in {@link WebhookWorker}.
 */
@RestController
public class WebhookController {

    static final int MAX_BODY_BYTES = 256 * 1024;

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final ChatwootProperties chatwoot;
    private final WebhookIntake intake;
    private final ObjectMapper mapper;

    public WebhookController(ChatwootProperties chatwoot, WebhookIntake intake, ObjectMapper mapper) {
        this.chatwoot = chatwoot;
        this.intake = intake;
        this.mapper = mapper;
    }

    @PostMapping("/api/webhooks/chatwoot/agent-bot")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = "X-Chatwoot-Delivery", required = false) String delivery,
            @RequestHeader(value = "X-Chatwoot-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Chatwoot-Signature", required = false) String signature) {
        if (body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "payload too large"));
        }
        AgentBotSignatureVerifier.Result verdict = AgentBotSignatureVerifier.verify(
                chatwoot.botSecret(), timestamp, signature, body, Instant.now());
        if (verdict != AgentBotSignatureVerifier.Result.VALID) {
            log.warn("webhook rejected delivery={} reason={}", delivery, verdict);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "malformed json"));
        }
        ChatwootEvent event = ChatwootEvent.parse(root);
        if (event.kind() == ChatwootEvent.Kind.IGNORED
                || event.accountId() != chatwoot.accountId() || event.inboxId() != chatwoot.inboxId()) {
            return ResponseEntity.accepted().body(Map.of("status", "ignored"));
        }
        boolean queued = intake.accept(event, delivery);
        log.info("webhook delivery={} kind={} conversation={} message={} status={}", delivery, event.kind(),
                event.conversationId(), event.messageId(), queued ? "queued" : "duplicate");
        return ResponseEntity.accepted().body(Map.of("status", queued ? "accepted" : "duplicate"));
    }
}
