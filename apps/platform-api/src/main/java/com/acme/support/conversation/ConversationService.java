package com.acme.support.conversation;

import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns conversation_control. Every ownership change runs inside {@link #inLock} so it is serialized per
 * conversation with a row lock (SELECT ... FOR UPDATE) and bumps the version.
 */
@Service
public class ConversationService {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public ConversationService(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Reads (creating on first sight) without holding a lock. Use for early checks only. */
    public ConversationControl current(long conversationId) {
        return tx.execute(s -> {
            jdbc.sql("""
                    insert into conversation_control (chatwoot_conversation_id, tenant_key, state, version, handoff_generation)
                    values (:c, 'acme-demo', 'AI_ACTIVE', 0, 0)
                    on conflict (chatwoot_conversation_id) do nothing
                    """).param("c", conversationId).update();
            return find(conversationId);
        });
    }

    /** Runs fn under a row lock. fn may call {@link #move}; the whole unit commits or rolls back together. */
    public <T> T inLock(long conversationId, Function<ConversationControl, T> fn) {
        return tx.execute(s -> {
            jdbc.sql("""
                    insert into conversation_control (chatwoot_conversation_id, tenant_key, state, version, handoff_generation)
                    values (:c, 'acme-demo', 'AI_ACTIVE', 0, 0)
                    on conflict (chatwoot_conversation_id) do nothing
                    """).param("c", conversationId).update();
            ConversationControl locked = jdbc.sql("""
                    select chatwoot_conversation_id, state, version, handoff_generation
                    from conversation_control where chatwoot_conversation_id = :c for update
                    """).param("c", conversationId).query(ConversationService::map).single();
            return fn.apply(locked);
        });
    }

    /** Must be called inside {@link #inLock}. Returns the new control record. */
    public ConversationControl move(ConversationControl current, ConversationState to) {
        if (!current.state().canMoveTo(to)) {
            throw new IllegalStateException("illegal conversation transition " + current.state() + " -> " + to);
        }
        if (current.state() == to) {
            return current;
        }
        return jdbc.sql("""
                update conversation_control set state = :to, version = version + 1, updated_at = now()
                where chatwoot_conversation_id = :c
                returning chatwoot_conversation_id, state, version, handoff_generation
                """).param("to", to.name()).param("c", current.chatwootConversationId())
                .query(ConversationService::map).single();
    }

    /** Must be called inside {@link #inLock}. Starts a new handoff generation (used on resume). */
    public ConversationControl bumpGeneration(ConversationControl current) {
        return jdbc.sql("""
                update conversation_control set handoff_generation = handoff_generation + 1, version = version + 1, updated_at = now()
                where chatwoot_conversation_id = :c
                returning chatwoot_conversation_id, state, version, handoff_generation
                """).param("c", current.chatwootConversationId()).query(ConversationService::map).single();
    }

    public void recordProcessed(long conversationId, long messageId) {
        jdbc.sql("update conversation_control set last_processed_message_id = :m, updated_at = now() where chatwoot_conversation_id = :c")
                .param("m", messageId).param("c", conversationId).update();
    }

    private ConversationControl find(long conversationId) {
        return jdbc.sql("select chatwoot_conversation_id, state, version, handoff_generation from conversation_control where chatwoot_conversation_id = :c")
                .param("c", conversationId).query(ConversationService::map).single();
    }

    private static ConversationControl map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ConversationControl(rs.getLong("chatwoot_conversation_id"),
                ConversationState.valueOf(rs.getString("state")), rs.getLong("version"), rs.getInt("handoff_generation"));
    }
}
