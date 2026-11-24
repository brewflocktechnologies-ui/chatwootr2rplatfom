package com.acme.support.agent;

import com.acme.support.chatwoot.ChatwootClient;
import com.acme.support.chatwoot.ConversationSnapshot;
import com.acme.support.conversation.ConversationControl;
import com.acme.support.conversation.ConversationService;
import com.acme.support.conversation.ConversationState;
import com.acme.support.handoff.HandoffReason;
import com.acme.support.handoff.HandoffService;
import com.acme.support.knowledge.R2rClient;
import com.acme.support.knowledge.RagResult;
import com.acme.support.shared.Retry;
import com.acme.support.shared.UpstreamException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * One customer turn. Order: ownership gate, human-request check, RAG with evidence and coverage gates,
 * grounding check, then a guarded send. The send writes a durable SENDING marker in its own committed
 * transaction before the Chatwoot call, so a crash after the post can never produce a second reply.
 * Outcome codes are returned as plain strings and stored on the webhook row. No model reasoning is stored.
 *
 * Single-node note: the conversation row lock is held only for the state and version check and the SENDING
 * marker, never across the Chatwoot HTTP call.
 */
@Service
public class AgentTurnService {

    private static final Logger log = LoggerFactory.getLogger(AgentTurnService.class);
    private static final int MAX_REPLY_CHARS = 1500;

    private final ConversationService conversations;
    private final HandoffService handoff;
    private final ChatwootClient chatwoot;
    private final R2rClient r2r;
    private final AgentProperties props;
    private final JdbcClient jdbc;

    public AgentTurnService(ConversationService conversations, HandoffService handoff, ChatwootClient chatwoot,
                            R2rClient r2r, AgentProperties props, JdbcClient jdbc) {
        this.conversations = conversations;
        this.handoff = handoff;
        this.chatwoot = chatwoot;
        this.r2r = r2r;
        this.props = props;
        this.jdbc = jdbc;
    }

    /** Called only for a verified, incoming, non-private customer message. */
    public String handleCustomerMessage(UUID webhookId, long conversationId, long messageId, String text) {
        ConversationControl c = conversations.current(conversationId);
        if (c.state() == ConversationState.HANDOFF_PENDING) {
            handoff.drive(conversationId);
            return "HANDOFF_COMPLETED";
        }
        if (c.state() != ConversationState.AI_ACTIVE) {
            return "NOT_AI_ACTIVE";
        }
        String prior = beginExecution(webhookId, conversationId, messageId);
        if ("SEND_OUTCOME_UNKNOWN".equals(prior)) {
            // An earlier attempt reached the send step and may have delivered a reply. Never send again.
            return handOff(webhookId, conversationId, HandoffReason.SEND_OUTCOME_UNKNOWN, text,
                    "An earlier AI reply attempt for this message has an unknown outcome.",
                    List.of("An AI reply may or may not have been delivered to the customer; the AI will not retry the send."),
                    List.of());
        }
        if (prior != null) {
            return "ALREADY_" + prior;
        }

        if (text == null || text.isBlank()) {
            return "EMPTY_MESSAGE";
        }
        if (HumanRequestMatcher.isHumanRequest(text)) {
            return handOff(webhookId, conversationId, HandoffReason.CUSTOMER_REQUESTED_HUMAN, text,
                    "Customer asked to speak with a person.",
                    List.of("Deterministic human-request phrase matched; knowledge base not searched."), List.of());
        }

        RagResult rag;
        try {
            rag = retrieve(text);
        } catch (UpstreamException e) {
            log.warn("turn webhook={} conversation={} knowledge call failed status={} transient={}",
                    webhookId, conversationId, e.status(), e.isTransient());
            return handOff(webhookId, conversationId, HandoffReason.KNOWLEDGE_UNAVAILABLE, text,
                    "Customer asked a knowledge question.",
                    List.of("Searched tenant knowledge base; the service failed after retries (HTTP " + e.status() + ")."),
                    List.of());
        }

        double threshold = props.evidenceMinScore();
        double top = EvidencePolicy.topScore(rag.chunks());
        if (!EvidencePolicy.hasEvidence(rag.chunks(), threshold)) {
            log.info("turn webhook={} conversation={} evidence=insufficient chunks={} topScore={}",
                    webhookId, conversationId, rag.chunks().size(), String.format("%.3f", top));
            return handOff(webhookId, conversationId, HandoffReason.NO_EVIDENCE, text,
                    "Customer asked a question that the knowledge base did not clearly answer.",
                    List.of("Searched tenant knowledge base; top score " + String.format("%.2f", top)
                            + " below threshold " + String.format("%.2f", threshold) + "."),
                    List.of());
        }
        List<String> titles = EvidencePolicy.sourceTitles(rag.chunks(), threshold);
        if (AnswerGrounding.isRefusal(rag.answer())) {
            return handOff(webhookId, conversationId, HandoffReason.NO_EVIDENCE, text,
                    "Customer asked a question the knowledge base does not cover.",
                    List.of("Searched tenant knowledge base; the model reported that no source answers the question."),
                    List.of());
        }

        // Coverage gate: every content term of the question must occur in the cited evidence.
        // Applies before any model answer is used, including the regenerated and fallback paths.
        String coverageEvidence = AnswerGrounding.evidenceText(rag.chunks(), threshold);
        if (!AnswerGrounding.covered(text, coverageEvidence)) {
            log.info("turn webhook={} conversation={} coverage=uncovered", webhookId, conversationId);
            return handOff(webhookId, conversationId, HandoffReason.NO_EVIDENCE, text,
                    "Customer asked about a topic the knowledge base does not cover.",
                    List.of("Searched tenant knowledge base; part of the question is not covered by the retrieved policy text."),
                    List.of());
        }

        Set<String> qStems = AnswerGrounding.questionStems(text);
        List<String> required = AnswerGrounding.requiredSentences(rag.chunks(), threshold, qStems);
        String evidence = AnswerGrounding.evidenceText(rag.chunks(), threshold);
        String answer = AnswerGrounding.trimAfterSource(rag.answer());
        AnswerGrounding.Outcome outcome = AnswerGrounding.Outcome.MATCHED;

        if (answer.isEmpty() || !AnswerGrounding.passes(answer, required, evidence)) {
            // One regeneration only. Fail closed on any upstream error during it.
            RagResult again;
            try {
                again = retrieve(text);
            } catch (UpstreamException e) {
                return handOff(webhookId, conversationId, HandoffReason.KNOWLEDGE_UNAVAILABLE, text,
                        "Customer asked a knowledge question.",
                        List.of("Grounding check failed and regeneration could not complete (HTTP " + e.status() + ")."),
                        List.of());
            }
            String second = AnswerGrounding.trimAfterSource(again.answer());
            if (AnswerGrounding.isRefusal(second)) {
                return handOff(webhookId, conversationId, HandoffReason.NO_EVIDENCE, text,
                        "Customer asked a question the knowledge base does not cover.",
                        List.of("Searched tenant knowledge base; the model reported that no source answers the question."),
                        List.of());
            }
            if (!second.isEmpty() && AnswerGrounding.passes(second, required, evidence)) {
                answer = second;
                outcome = AnswerGrounding.Outcome.REGENERATED;
            } else {
                // Quote the evidence sentences verbatim. Only evidence is shown, never model text.
                List<String> quote = required.isEmpty() ? List.of()
                        : (second.isEmpty() ? required : missingOrAll(second, required));
                if (quote.isEmpty()) {
                    return handOff(webhookId, conversationId, HandoffReason.NO_USABLE_ANSWER, text,
                            "Customer asked a knowledge question.",
                            List.of("Searched tenant knowledge base; no evidence sentence could be grounded in an answer."),
                            titles);
                }
                answer = String.join(" ", quote) + "\n\nSource: " + String.join(", ", titles);
                outcome = AnswerGrounding.Outcome.FALLBACK_QUOTED;
            }
        }
        log.info("turn webhook={} conversation={} grounding={}", webhookId, conversationId, outcome);

        String reply = AnswerGrounding.displayText(answer);
        if (!reply.contains("Source:")) {
            reply = reply + "\n\nSource: " + String.join(", ", titles);
        }
        String sent = sendGuarded(webhookId, conversationId, messageId, c.version(), truncate(reply), titles, top);
        if ("SEND_UNKNOWN".equals(sent)) {
            // The outcome is unknown: no retry. Hand over with a private note so a human checks the thread.
            return handOff(webhookId, conversationId, HandoffReason.SEND_OUTCOME_UNKNOWN, text,
                    "The AI reply attempt has an unknown outcome.",
                    List.of("An AI reply may or may not have been delivered to the customer; the AI will not retry the send."),
                    titles);
        }
        return sent;
    }

    /**
     * Guarded send. Order matters for crash safety:
     *   1. Read-only gates outside the row lock: Chatwoot eligibility, then a human reply newer than the customer message.
     *   2. Under the row lock, re-check state and version and write the SENDING marker. The transaction commits
     *      before step 3, so the marker survives a crash during the HTTP call.
     *   3. The Chatwoot post, outside any transaction and outside the row lock.
     *   4. Mark SENT. If this write fails after the post, the row stays SENDING and the next attempt opens a
     *      handoff instead of posting again.
     */
    private String sendGuarded(UUID webhookId, long conversationId, long messageId, long expectedVersion, String reply,
                               List<String> titles, double top) {
        ConversationSnapshot snap = chatwoot.getConversation(conversationId);
        if (!snap.aiEligible(chatwoot.agentBotId())) {
            return discard(webhookId, conversationId, titles, top,
                    "chatwoot conversation not AI-eligible (status " + snap.status() + ")");
        }
        if (chatwoot.hasHumanReplyAfter(conversationId, messageId)) {
            conversations.inLock(conversationId, locked -> {
                if (locked.state() == ConversationState.AI_ACTIVE) {
                    conversations.move(locked, ConversationState.HUMAN_ACTIVE);
                }
                return null;
            });
            markExecution(webhookId, "DISCARDED", "HUMAN_REPLY_QUEUED", titles, top, null,
                    "human reply newer than the customer message");
            log.info("turn webhook={} conversation={} discarded: human reply queued, ownership moved to human",
                    webhookId, conversationId);
            return "HUMAN_TOOK_OVER";
        }

        String gate = conversations.inLock(conversationId, locked -> {
            if (locked.state() != ConversationState.AI_ACTIVE || locked.version() != expectedVersion) {
                return "ownership or version changed before send";
            }
            markExecution(webhookId, "SENDING", "REPLY", titles, top, null, null);
            return null;
        });
        if (gate != null) {
            return discard(webhookId, conversationId, titles, top, gate);
        }

        long outboundId;
        try {
            outboundId = chatwoot.postMessage(conversationId, reply, false);
        } catch (RuntimeException e) {
            markExecution(webhookId, "SEND_UNKNOWN", "REPLY", titles, top, null, e.getClass().getSimpleName());
            return "SEND_UNKNOWN";
        }
        markExecution(webhookId, "SENT", "REPLY", titles, top, outboundId, null);
        log.info("turn webhook={} conversation={} reply sent sources={}", webhookId, conversationId, titles.size());
        return "SENT";
    }

    private String discard(UUID webhookId, long conversationId, List<String> titles, double top, String why) {
        markExecution(webhookId, "DISCARDED", "LATE_RESULT", titles, top, null, why);
        log.info("turn webhook={} conversation={} discarded: {}", webhookId, conversationId, why);
        return "DISCARDED";
    }

    private String handOff(UUID webhookId, long conversationId, HandoffReason reason, String text,
                           String intent, List<String> checks, List<String> sources) {
        boolean opened = handoff.open(conversationId, reason, intent, text, checks, sources);
        if (!opened) {
            return "NOT_AI_ACTIVE";
        }
        handoff.drive(conversationId);
        markExecution(webhookId, "HANDED_OFF", reason.name(), sources, 0.0, null, null);
        log.info("turn webhook={} conversation={} handed off reason={}", webhookId, conversationId, reason);
        return "HANDED_OFF";
    }

    /**
     * Creates the execution row once per webhook. Returns "SEND_OUTCOME_UNKNOWN" when an earlier attempt reached the
     * send step (SENDING or SEND_UNKNOWN), a prior terminal state, or null to proceed.
     */
    private String beginExecution(UUID webhookId, long conversationId, long messageId) {
        jdbc.sql("""
                insert into agent_execution (webhook_inbox_id, chatwoot_conversation_id, chatwoot_message_id, correlation_id, state, model)
                values (:w, :c, :m, :corr, 'RUNNING', :model)
                on conflict (webhook_inbox_id) do nothing
                """).param("w", webhookId).param("c", conversationId).param("m", messageId)
                .param("corr", "webhook-" + webhookId).param("model", props.model()).update();
        String state = jdbc.sql("select state from agent_execution where webhook_inbox_id = :w")
                .param("w", webhookId).query(String.class).single();
        if ("SENDING".equals(state) || "SEND_UNKNOWN".equals(state)) {
            return "SEND_OUTCOME_UNKNOWN";
        }
        return "RUNNING".equals(state) ? null : state;
    }

    private void markExecution(UUID webhookId, String state, String reasonCode, List<String> titles,
                               double top, Long outboundId, String failure) {
        jdbc.sql("""
                update agent_execution set state = :s, reason_code = :r, top_score = :t,
                    source_titles = :src, outbound_message_id = coalesce(cast(:o as bigint), outbound_message_id),
                    failure_reason = :f,
                    finished_at = case when :s in ('SENT','HANDED_OFF','DISCARDED','SKIPPED','FAILED','SEND_UNKNOWN') then now() else finished_at end
                where webhook_inbox_id = :w
                """).param("s", state).param("r", reasonCode).param("t", top)
                .param("src", String.join(" | ", titles)).param("o", outboundId).param("f", failure)
                .param("w", webhookId).update();
    }

    private RagResult retrieve(String text) {
        // R2R limits RAG to one concurrent request (429 when busy); back off long enough for a slot to free.
        return Retry.run(4, Duration.ofSeconds(2), () -> r2r.rag(text, props.retrievalLimit(), props.model()));
    }

    /** Required sentences the answer lacks; if none are reported missing, the full required set is quoted. */
    private static List<String> missingOrAll(String answer, List<String> required) {
        List<String> missing = AnswerGrounding.missing(answer, required);
        return missing.isEmpty() ? required : missing;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_REPLY_CHARS ? s : s.substring(0, MAX_REPLY_CHARS);
    }
}
