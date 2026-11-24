package com.acme.support.webhook;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** PostgreSQL-backed durable inbox. Dedupe key is unique; claims use FOR UPDATE SKIP LOCKED with a lease. */
@Component
public class WebhookInbox {

    private final JdbcClient jdbc;

    public WebhookInbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Claimed(UUID id, ChatwootEvent.Kind kind, long conversationId, long messageId, String customerText, int attempts) {
    }

    public record Row(UUID id, String status, String eventKind, long conversationId, long messageId, int attempts,
                      String outcome, String lastError, Instant receivedAt) {
    }

    /** True when this delivery was newly queued; false when the same event was already accepted (duplicate). */
    public boolean enqueue(ChatwootEvent e, String deliveryId) {
        int n = jdbc.sql("""
                insert into webhook_inbox (tenant_key, dedupe_key, delivery_id, event_kind, chatwoot_conversation_id,
                    chatwoot_message_id, customer_text, status)
                values ('acme-demo', :k, :d, :kind, :c, :m, :t, 'QUEUED')
                on conflict (dedupe_key) do nothing
                """).param("k", e.dedupeKey()).param("d", deliveryId)
                .param("kind", e.kind().name()).param("c", e.conversationId()).param("m", e.messageId())
                .param("t", e.content()).update();
        return n == 1;
    }

    /** Claims one due row (or an expired lease) for this worker. Single-node MVP; SKIP LOCKED keeps it safe. */
    public Optional<Claimed> claim(String owner, Duration lease) {
        return jdbc.sql("""
                update webhook_inbox w
                set status = 'PROCESSING', lease_owner = :o,
                    lease_expires_at = now() + make_interval(secs => :lease), attempts = w.attempts + 1, updated_at = now()
                where w.id = (
                    select id from webhook_inbox
                    where (status in ('QUEUED', 'RETRY') and next_attempt_at <= now())
                       or (status = 'PROCESSING' and lease_expires_at < now())
                    order by received_at
                    limit 1
                    for update skip locked)
                returning w.id, w.event_kind, w.chatwoot_conversation_id, w.chatwoot_message_id, w.customer_text, w.attempts
                """).param("o", owner).param("lease", lease.toSeconds())
                .query((ResultSet rs, int n) -> new Claimed(
                        rs.getObject("id", UUID.class),
                        ChatwootEvent.Kind.valueOf(rs.getString("event_kind")),
                        rs.getLong("chatwoot_conversation_id"),
                        rs.getLong("chatwoot_message_id"),
                        rs.getString("customer_text"),
                        rs.getInt("attempts")))
                .optional();
    }

    public void done(UUID id, String outcome) {
        jdbc.sql("""
                update webhook_inbox set status = 'PROCESSED', outcome = :o, lease_owner = null, lease_expires_at = null,
                    customer_text = null, last_error = null, updated_at = now()
                where id = :id
                """).param("o", outcome).param("id", id).update();
    }

    public void retryLater(UUID id, Duration delay, String error) {
        jdbc.sql("""
                update webhook_inbox set status = 'RETRY', next_attempt_at = now() + make_interval(secs => :s),
                    lease_owner = null, lease_expires_at = null, last_error = :e, updated_at = now()
                where id = :id
                """).param("s", delay.toMillis() / 1000.0).param("e", error).param("id", id).update();
    }

    public void failed(UUID id, String error) {
        jdbc.sql("""
                update webhook_inbox set status = 'FAILED', lease_owner = null, lease_expires_at = null, last_error = :e,
                    customer_text = null, updated_at = now()
                where id = :id
                """).param("e", error).param("id", id).update();
    }

    public List<Row> list(String status, int limit) {
        String where = status == null ? "true" : "status = :s";
        var q = jdbc.sql("""
                select id, status, event_kind, chatwoot_conversation_id, chatwoot_message_id, attempts, outcome, last_error, received_at
                from webhook_inbox""" + " where " + where + " order by received_at desc limit :l");
        if (status != null) {
            q = q.param("s", status);
        }
        return q.param("l", limit).query(WebhookInbox::map).list();
    }

    public Map<String, Long> countsByStatus() {
        var out = new java.util.LinkedHashMap<String, Long>();
        jdbc.sql("select status, count(*) as n from webhook_inbox group by status order by status")
                .query((ResultSet rs, int n) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .list().forEach(en -> out.put(en.getKey(), en.getValue()));
        return out;
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("event_kind"),
                rs.getLong("chatwoot_conversation_id"), rs.getLong("chatwoot_message_id"), rs.getInt("attempts"),
                rs.getString("outcome"), rs.getString("last_error"),
                rs.getObject("received_at", OffsetDateTime.class).toInstant());
    }
}
