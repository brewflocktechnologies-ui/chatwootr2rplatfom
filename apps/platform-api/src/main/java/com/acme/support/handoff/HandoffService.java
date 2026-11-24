package com.acme.support.handoff;

import com.acme.support.chatwoot.ChatwootClient;
import com.acme.support.conversation.ConversationControl;
import com.acme.support.conversation.ConversationService;
import com.acme.support.conversation.ConversationState;
import com.acme.support.shared.ConflictException;
import com.acme.support.shared.UpstreamException;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Handoff state machine: AI_ACTIVE -> HANDOFF_PENDING (local, first) -> private note -> Chatwoot open -> HUMAN_ACTIVE.
 * Every step is idempotent so webhook redelivery and Chatwoot timeouts cannot create a second note.
 */
@Service
public class HandoffService {

    private final ConversationService conversations;
    private final ChatwootClient chatwoot;
    private final JdbcClient jdbc;

    public HandoffService(ConversationService conversations, ChatwootClient chatwoot, JdbcClient jdbc) {
        this.conversations = conversations;
        this.chatwoot = chatwoot;
        this.jdbc = jdbc;
    }

    /**
     * Records the decision and stops AI ownership. Returns false when a human already owns the conversation.
     * Calling again for the same generation keeps the first recorded note body.
     */
    public boolean open(long conversationId, HandoffReason reason, String intent, String customerText,
                        List<String> checks, List<String> sourceTitles) {
        return conversations.inLock(conversationId, c -> {
            if (c.state() == ConversationState.HUMAN_ACTIVE) {
                return false;
            }
            if (c.state() == ConversationState.AI_ACTIVE) {
                conversations.move(c, ConversationState.HANDOFF_PENDING);
            }
            String key = handoffKey(conversationId, c.handoffGeneration());
            String body = HandoffNote.build(key, reason, intent, customerText, checks, sourceTitles);
            jdbc.sql("""
                    insert into handoff_record (tenant_key, chatwoot_conversation_id, handoff_generation, handoff_key, reason, note_body, status)
                    values ('acme-demo', :c, :g, :k, :r, :b, 'PENDING')
                    on conflict (chatwoot_conversation_id, handoff_generation) do nothing
                    """).param("c", conversationId).param("g", c.handoffGeneration()).param("k", key)
                    .param("r", reason.name()).param("b", body).update();
            return true;
        });
    }

    /**
     * Completes a pending handoff: note (once), Chatwoot open, then HUMAN_ACTIVE. Safe to call repeatedly.
     * Throws UpstreamException on transient Chatwoot failure; the caller retries later.
     */
    public void drive(long conversationId) {
        ConversationControl c = conversations.current(conversationId);
        if (c.state() != ConversationState.HANDOFF_PENDING) {
            return;
        }
        PendingNote rec = jdbc.sql("""
                select handoff_key, note_body, note_message_id from handoff_record
                where chatwoot_conversation_id = :c and handoff_generation = :g
                """).param("c", conversationId).param("g", c.handoffGeneration())
                .query((rs, n) -> new PendingNote(rs.getString("handoff_key"), rs.getString("note_body"),
                        (Long) rs.getObject("note_message_id")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("HANDOFF_PENDING without a handoff record for conversation " + conversationId));
        if (rec.noteMessageId() == null) {
            postNoteOnce(conversationId, rec.key(), rec.body());
        }
        chatwoot.toggleStatus(conversationId, "open");
        conversations.inLock(conversationId, locked -> {
            if (locked.state() == ConversationState.HANDOFF_PENDING) {
                conversations.move(locked, ConversationState.HUMAN_ACTIVE);
            }
            jdbc.sql("""
                    update handoff_record set status = 'TRANSFERRED', updated_at = now()
                    where chatwoot_conversation_id = :c and handoff_generation = :g and status in ('PENDING', 'NOTED')
                    """).param("c", conversationId).param("g", locked.handoffGeneration()).update();
            return null;
        });
    }

    /** Explicit, audited, operator-initiated resume. Never called automatically. */
    public void resume(long conversationId, String actor) {
        conversations.inLock(conversationId, c -> {
            if (c.state() != ConversationState.HANDOFF_PENDING && c.state() != ConversationState.HUMAN_ACTIVE) {
                throw new ConflictException("resume requires HANDOFF_PENDING or HUMAN_ACTIVE, current state is " + c.state());
            }
            chatwoot.toggleStatus(conversationId, "pending");
            jdbc.sql("""
                    update handoff_record set status = 'RESUMED', updated_at = now()
                    where chatwoot_conversation_id = :c and handoff_generation = :g
                    """).param("c", conversationId).param("g", c.handoffGeneration()).update();
            ConversationControl moved = conversations.move(c, ConversationState.AI_ACTIVE);
            conversations.bumpGeneration(moved);
            jdbc.sql("""
                    insert into admin_audit (actor, action, target, detail)
                    values (:a, 'CONVERSATION_RESUME_AI', :t, :d)
                    """).param("a", actor).param("t", "chatwoot-conversation:" + conversationId)
                    .param("d", "from " + c.state() + " to AI_ACTIVE, chatwoot status pending").update();
            return null;
        });
    }

    /** Private note: at most one per handoff key. A timeout is resolved by searching for the marker first. */
    private void postNoteOnce(long conversationId, String key, String body) {
        // A crash after the post but before note_message_id was saved leaves no local record: search first.
        Long noteId = chatwoot.findPrivateNoteWithMarker(conversationId, key).orElse(null);
        if (noteId == null) {
            try {
                noteId = chatwoot.postMessage(conversationId, body, true);
            } catch (UpstreamException e) {
                if (!e.isTransient()) {
                    throw e;
                }
                noteId = chatwoot.findPrivateNoteWithMarker(conversationId, key).orElseThrow(() -> e);
            }
        }
        jdbc.sql("""
                update handoff_record set note_message_id = :m, status = 'NOTED', updated_at = now()
                where handoff_key = :k and note_message_id is null
                """).param("m", noteId).param("k", key).update();
    }

    private record PendingNote(String key, String body, Long noteMessageId) {
    }

    public static String handoffKey(long conversationId, int generation) {
        return "acme-demo/conversation-" + conversationId + "/generation-" + generation;
    }
}
