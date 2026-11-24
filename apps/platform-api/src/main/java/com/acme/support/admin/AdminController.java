package com.acme.support.admin;

import com.acme.support.agent.AgentProperties;
import com.acme.support.chatwoot.ChatwootClient;
import com.acme.support.chatwoot.ChatwootProperties;
import com.acme.support.handoff.HandoffService;
import com.acme.support.knowledge.KnowledgeService;
import com.acme.support.knowledge.R2rClient;
import com.acme.support.shared.NotFoundException;
import com.acme.support.tenant.TenantService;
import com.acme.support.webhook.WebhookInbox;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Admin API. Response shapes are frozen in docs/architecture/api-contract.md ("Response shapes"). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Set<String> WEBHOOK_STATUSES = Set.of("QUEUED", "PROCESSING", "RETRY", "PROCESSED", "FAILED");
    private static final List<String> STATES = List.of("AI_ACTIVE", "HANDOFF_PENDING", "HUMAN_ACTIVE", "RESOLVED");

    private final TenantService tenant;
    private final ChatwootProperties chatwootProps;
    private final AgentProperties agent;
    private final KnowledgeService knowledge;
    private final HandoffService handoff;
    private final WebhookInbox inbox;
    private final ChatwootClient chatwoot;
    private final R2rClient r2r;
    private final JdbcClient jdbc;
    private final OllamaProbe ollamaProbe;

    public AdminController(TenantService tenant, ChatwootProperties chatwootProps, AgentProperties agent,
                           KnowledgeService knowledge, HandoffService handoff, WebhookInbox inbox,
                           ChatwootClient chatwoot, R2rClient r2r, JdbcClient jdbc, OllamaProbe ollamaProbe) {
        this.tenant = tenant;
        this.chatwootProps = chatwootProps;
        this.agent = agent;
        this.knowledge = knowledge;
        this.handoff = handoff;
        this.inbox = inbox;
        this.chatwoot = chatwoot;
        this.r2r = r2r;
        this.jdbc = jdbc;
        this.ollamaProbe = ollamaProbe;
    }

    public record DocumentDto(String id, String filename, String status, String errorCode, String sha256,
                              long sizeBytes, String updatedAt) {
        static DocumentDto of(KnowledgeService.KnowledgeView v) {
            boolean failed = "FAILED".equals(v.ingestionStatus());
            return new DocumentDto(v.id().toString(), v.fileName(), v.ingestionStatus(),
                    failed ? "INGESTION_FAILED" : null, v.sha256(), v.sizeBytes(), v.updatedAt().toString());
        }
    }

    @GetMapping("/diagnostics")
    public Map<String, Object> diagnostics() {
        List<Map<String, Object>> services = new ArrayList<>();
        services.add(service("chatwoot", chatwoot.ping() ? "UP" : "DOWN", null));
        services.add(service("r2r", r2r.health() ? "UP" : "DOWN", null));
        OllamaProbe.Result ollama = ollamaProbe.probe();
        services.add(service("ollama", ollama.state(), ollama.detail()));
        services.add(service("platform-db", dbUp() ? "UP" : "DOWN", null));

        Map<String, Long> counts = new LinkedHashMap<>();
        STATES.forEach(s -> counts.put(s, 0L));
        jdbc.sql("select state, count(*) as n from conversation_control group by state")
                .query((rs, n) -> Map.entry(rs.getString("state"), rs.getLong("n")))
                .list().forEach(e -> counts.put(e.getKey(), e.getValue()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("services", services);
        out.put("tenant", Map.of("tenantKey", tenant.tenantKey()));
        out.put("conversationCounts", counts);
        out.put("failedWebhooks", inbox.countsByStatus().getOrDefault("FAILED", 0L));
        return out;
    }

    @GetMapping("/tenant")
    public Map<String, Object> tenant() {
        return tenant.summary();
    }

    @GetMapping("/agent")
    public Map<String, Object> agent() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Acme Demo Support Agent");
        m.put("model", agent.model());
        m.put("embeddingModel", agent.embeddingModel());
        m.put("knowledgeScope", "tenant collection only (server-side scoped, web search disabled)");
        m.put("handoffPolicy", "Explicit requests for a human hand off before any knowledge lookup; "
                + "answers need evidence above the score threshold; the AI never resumes automatically.");
        return m;
    }

    @GetMapping("/knowledge/documents")
    public Map<String, Object> documents() {
        return Map.of("documents", knowledge.list().stream().map(DocumentDto::of).toList());
    }

    @PostMapping(path = "/knowledge/documents", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        KnowledgeService.Upload result = knowledge.upload(file.getOriginalFilename(), file.getBytes());
        // An identical file already stored returns the existing record with 200 (no second R2R copy).
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(Map.of("document", DocumentDto.of(result.record())));
    }

    @PostMapping("/knowledge/documents/{id}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable UUID id) {
        KnowledgeService.KnowledgeView v = knowledge.retry(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("document", DocumentDto.of(v)));
    }

    @GetMapping("/conversations/{id}")
    public Map<String, Object> conversation(@PathVariable long id) {
        Map<String, Object> control = jdbc.sql("""
                select state, version, handoff_generation, updated_at from conversation_control where chatwoot_conversation_id = :c
                """).param("c", id).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chatwootConversationId", id);
            m.put("state", rs.getString("state"));
            m.put("version", rs.getLong("version"));
            m.put("handoffGeneration", rs.getInt("handoff_generation"));
            m.put("updatedAt", rs.getObject("updated_at", java.time.OffsetDateTime.class).toString());
            return m;
        }).optional().orElseThrow(() -> new NotFoundException("conversation not known to the platform"));
        Map<String, Object> latestHandoff = jdbc.sql("""
                select reason, status, note_message_id, created_at from handoff_record
                where chatwoot_conversation_id = :c order by created_at desc limit 1
                """).param("c", id).query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reason", rs.getString("reason"));
            m.put("status", rs.getString("status"));
            m.put("noteMessageId", rs.getObject("note_message_id"));
            m.put("createdAt", rs.getObject("created_at", java.time.OffsetDateTime.class).toString());
            return m;
        }).optional().orElse(null);
        control.put("handoff", latestHandoff);
        return control;
    }

    @PostMapping("/conversations/{id}/resume")
    public Map<String, Object> resume(@PathVariable long id) {
        handoff.resume(id, "admin-api-key");
        return Map.of("chatwootConversationId", id, "state", "AI_ACTIVE", "chatwootStatus", "pending");
    }

    @GetMapping("/webhooks")
    public Map<String, Object> webhooks(@RequestParam(value = "status", required = false) String status,
                                        @RequestParam(value = "limit", defaultValue = "50") int limit) {
        if (status != null && !WEBHOOK_STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be one of " + WEBHOOK_STATUSES);
        }
        return Map.of("webhooks", inbox.list(status, Math.min(Math.max(limit, 1), 200)).stream()
                .map(AdminController::webhookView).toList());
    }

    private Map<String, Object> service(String name, String state, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("state", state);
        m.put("detail", detail);
        return m;
    }

    private boolean dbUp() {
        try {
            jdbc.sql("select 1").query(Integer.class).single();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Map<String, Object> webhookView(WebhookInbox.Row r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("status", r.status());
        m.put("eventKind", r.eventKind());
        m.put("chatwootConversationId", r.conversationId());
        m.put("chatwootMessageId", r.messageId());
        m.put("attempts", r.attempts());
        m.put("outcome", r.outcome());
        m.put("lastError", r.lastError());
        m.put("receivedAt", r.receivedAt().toString());
        return m;
    }
}
