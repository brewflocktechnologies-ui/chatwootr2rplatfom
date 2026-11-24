package com.acme.support.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.acme.support.AbstractIntegrationTest;
import com.acme.support.chatwoot.ChatwootClient;
import com.acme.support.chatwoot.ConversationSnapshot;
import com.acme.support.conversation.ConversationControl;
import com.acme.support.conversation.ConversationService;
import com.acme.support.conversation.ConversationState;
import com.acme.support.handoff.HandoffService;
import com.acme.support.knowledge.R2rClient;
import com.acme.support.knowledge.RagResult;
import com.acme.support.shared.UpstreamException;
import com.acme.support.webhook.ChatwootEvent;
import com.acme.support.webhook.WebhookInbox;
import com.acme.support.webhook.WebhookWorker;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Full turn flow against real PostgreSQL. Chatwoot and R2R are mocked at their adapter boundary. */
class AgentTurnIntegrationTest extends AbstractIntegrationTest {

    private static final String POLICY = "Acme Demo Support Policy (FICTIONAL)";
    private static final long CONV = 1L;

    @MockitoBean
    ChatwootClient chatwoot;

    @MockitoBean
    R2rClient r2r;

    @Autowired
    WebhookInbox inbox;

    @Autowired
    WebhookWorker worker;

    @Autowired
    ConversationService conversations;

    @Autowired
    HandoffService handoff;

    @BeforeEach
    void setUp() {
        resetPlatformState();
        org.mockito.Mockito.reset(chatwoot, r2r);
        when(chatwoot.getConversation(anyLong())).thenReturn(new ConversationSnapshot(CONV, "open", null));
        when(chatwoot.postMessage(anyLong(), anyString(), anyBoolean())).thenReturn(555L);
        when(chatwoot.findPrivateNoteWithMarker(anyLong(), anyString())).thenReturn(Optional.empty());
        when(r2r.rag(anyString(), anyInt(), anyString())).thenReturn(policyAnswer(0.649));
    }

    @Test
    void groundedAnswerIsSentOnceWithRealSourceTitle() {
        process(7L, "What is your standard delivery time?");

        verify(chatwoot).postMessage(eq(CONV), contains("Source: " + POLICY), eq(false));
        assertThat(outcome(7L)).isEqualTo("SENT");
        assertThat(executionState(7L)).isEqualTo("SENT");
        assertThat(state()).isEqualTo(ConversationState.AI_ACTIVE);
    }

    @Test
    void humanRequestHandsOffBeforeAnyRetrievalAndPostsOnePrivateNote() {
        process(8L, "I need to speak to a human agent, please.");

        verify(r2r, never()).rag(anyString(), anyInt(), anyString());
        verify(chatwoot, times(1)).postMessage(eq(CONV), contains("Handoff ref: acme-demo/conversation-1/generation-0"), eq(true));
        verify(chatwoot, never()).postMessage(anyLong(), anyString(), eq(false));
        verify(chatwoot).toggleStatus(CONV, "open");
        assertThat(state()).isEqualTo(ConversationState.HUMAN_ACTIVE);
        assertThat(outcome(8L)).isEqualTo("HANDED_OFF");
    }

    @Test
    void humanOwnedConversationGetsNoAiAnswerForLaterCustomerMessages() {
        process(9L, "I need to speak to a human agent, please.");
        org.mockito.Mockito.clearInvocations(r2r, chatwoot);

        process(10L, "Can you also check this for me?");

        verify(r2r, never()).rag(anyString(), anyInt(), anyString());
        verify(chatwoot, never()).postMessage(anyLong(), anyString(), eq(false));
        assertThat(outcome(10L)).isEqualTo("NOT_AI_ACTIVE");
        assertThat(state()).isEqualTo(ConversationState.HUMAN_ACTIVE);
    }

    @Test
    void lowScoreEvidenceHandsOffInsteadOfInventing() {
        when(r2r.rag(anyString(), anyInt(), anyString())).thenReturn(policyAnswer(0.265));

        process(11L, "What is the capital of France?");

        verify(chatwoot, never()).postMessage(anyLong(), anyString(), eq(false));
        verify(chatwoot).postMessage(eq(CONV), contains("below threshold 0.50"), eq(true));
        assertThat(outcome(11L)).isEqualTo("HANDED_OFF");
        assertThat(state()).isEqualTo(ConversationState.HUMAN_ACTIVE);
    }

    @Test
    void knowledgeFailureFailsClosedAfterBoundedRetries() {
        when(r2r.rag(anyString(), anyInt(), anyString()))
                .thenThrow(new UpstreamException("r2r", 500, true, "r2r rag failed with HTTP 500", null));

        process(12L, "What is your standard delivery time?");

        verify(r2r, times(4)).rag(anyString(), anyInt(), anyString());
        verify(chatwoot, never()).postMessage(anyLong(), anyString(), eq(false));
        verify(chatwoot).postMessage(eq(CONV), contains("Handoff ref"), eq(true));
        assertThat(outcome(12L)).isEqualTo("HANDED_OFF");
    }

    @Test
    void timedOutPrivateNoteIsFoundByMarkerAndNotPostedTwice() {
        when(chatwoot.postMessage(anyLong(), anyString(), eq(true)))
                .thenThrow(new UpstreamException("chatwoot", 0, true, "chatwoot postMessage transport error", null));
        when(chatwoot.findPrivateNoteWithMarker(eq(CONV), contains("generation-0")))
                .thenReturn(Optional.empty(), Optional.of(77L));

        process(13L, "I need a human, please");

        verify(chatwoot, times(1)).postMessage(eq(CONV), anyString(), eq(true));
        verify(chatwoot).toggleStatus(CONV, "open");
        assertThat(outcome(13L)).isEqualTo("HANDED_OFF");
        assertThat(state()).isEqualTo(ConversationState.HUMAN_ACTIVE);
    }

    @Test
    void redeliveredWebhookNeverProducesSecondCustomerReply() {
        ChatwootEvent event = new ChatwootEvent(ChatwootEvent.Kind.CUSTOMER_MESSAGE, 4, 1, CONV, 14L,
                "What is your standard delivery time?");
        assertThat(inbox.enqueue(event, "d-1")).isTrue();
        worker.poll();
        assertThat(inbox.enqueue(event, "d-2")).as("same event + message id is a duplicate").isFalse();
        worker.poll();

        verify(chatwoot, times(1)).postMessage(anyLong(), anyString(), eq(false));
        assertThat(outcome(14L)).isEqualTo("SENT");
    }

    @Test
    void ownershipChangeBeforeSendDiscardsLateAnswer() {
        when(chatwoot.getConversation(anyLong())).thenReturn(new ConversationSnapshot(CONV, "open", 99L));

        process(15L, "What is your standard delivery time?");

        verify(chatwoot, never()).postMessage(anyLong(), anyString(), eq(false));
        assertThat(outcome(15L)).isEqualTo("DISCARDED");
        assertThat(executionState(15L)).isEqualTo("DISCARDED");
    }

    @Test
    void uncertainSendIsNeverRetriedAndHandsOffWithANote() {
        when(chatwoot.postMessage(anyLong(), anyString(), eq(false)))
                .thenThrow(new UpstreamException("chatwoot", 0, true, "chatwoot postMessage transport error", null));

        process(16L, "What is your standard delivery time?");
        process(16L, "What is your standard delivery time?");

        verify(chatwoot, times(1)).postMessage(anyLong(), anyString(), eq(false));
        verify(chatwoot, times(1)).postMessage(eq(CONV),
                contains("may or may not have been delivered"), eq(true));
        assertThat(state()).isEqualTo(ConversationState.HUMAN_ACTIVE);
        assertThat(jdbc.sql("select reason_code from agent_execution e join webhook_inbox w on w.id = e.webhook_inbox_id where w.dedupe_key = 'chatwoot:4:message_created:16'").query(String.class).single())
                .isEqualTo("SEND_OUTCOME_UNKNOWN");
        assertThat(inbox.list("FAILED", 5)).isEmpty();
        assertThat(outcome(16L)).isEqualTo("HANDED_OFF");
    }

    @Test
    void failureAfterPostAndBeforeSentCommitLeavesSendingMarkerAndRetryNeverPostsAgain() {
        // Simulate a crash between the Chatwoot post and the SENT commit: the SENT update fails.
        jdbc.sql("""
                create or replace function fail_sent_marker() returns trigger language plpgsql as $$
                begin
                  if new.state = 'SENT' then raise exception 'simulated crash before SENT commit'; end if;
                  return new;
                end $$
                """).update();
        jdbc.sql("create trigger fail_sent_marker before update on agent_execution for each row execute function fail_sent_marker()")
                .update();
        try {
            process(41L, "What is your standard delivery time?");
        } finally {
            jdbc.sql("drop trigger if exists fail_sent_marker on agent_execution").update();
        }

        verify(chatwoot, times(1)).postMessage(anyLong(), anyString(), eq(false));
        assertThat(executionState(41L)).isEqualTo("SENDING");
        assertThat(outcome(41L)).startsWith("unexpected");

        // Redelivery after restart: the row is SENDING, so no second customer reply is ever posted.
        jdbc.sql("update webhook_inbox set status = 'QUEUED', next_attempt_at = now() where dedupe_key = 'chatwoot:4:message_created:41'")
                .update();
        worker.poll();

        verify(chatwoot, times(1)).postMessage(anyLong(), anyString(), eq(false));
        verify(chatwoot, times(1)).postMessage(eq(CONV), contains("may or may not have been delivered"), eq(true));
        assertThat(outcome(41L)).isEqualTo("HANDED_OFF");
        assertThat(state()).isEqualTo(ConversationState.HUMAN_ACTIVE);
    }

    @Test
    void queuedHumanReplyDiscardsTheAiSendAndMovesOwnershipToHuman() {
        when(chatwoot.hasHumanReplyAfter(anyLong(), anyLong())).thenReturn(true);

        process(42L, "What is your standard delivery time?");

        verify(chatwoot, never()).postMessage(anyLong(), anyString(), eq(false));
        assertThat(outcome(42L)).isEqualTo("HUMAN_TOOK_OVER");
        assertThat(executionState(42L)).isEqualTo("DISCARDED");
        assertThat(state()).isEqualTo(ConversationState.HUMAN_ACTIVE);
    }

    @Test
    void noteRecordedAsMissingIsNotPostedAgainWhenChatwootAlreadyHasIt() {
        // A crash after the note was posted but before note_message_id was saved.
        handoff.open(CONV, com.acme.support.handoff.HandoffReason.NO_EVIDENCE, "intent", "text",
                List.of("check"), List.of());
        when(chatwoot.findPrivateNoteWithMarker(eq(CONV), contains("generation-0"))).thenReturn(Optional.of(77L));

        handoff.drive(CONV);

        verify(chatwoot, never()).postMessage(anyLong(), anyString(), eq(true));
        assertThat(jdbc.sql("select note_message_id from handoff_record where chatwoot_conversation_id = 1")
                .query(Long.class).single()).isEqualTo(77L);
    }

    @Test
    void resumeIsExplicitAuditedAndOnlyThenAiAnswersAgain() {
        process(17L, "I need a human");
        org.mockito.Mockito.clearInvocations(r2r, chatwoot);

        handoff.resume(CONV, "test-operator");

        verify(chatwoot).toggleStatus(CONV, "pending");
        assertThat(state()).isEqualTo(ConversationState.AI_ACTIVE);
        assertThat(jdbc.sql("select count(*) from admin_audit where action = 'CONVERSATION_RESUME_AI' and actor = 'test-operator'")
                .query(Long.class).single()).isEqualTo(1L);
        verifyNoMoreInteractions(r2r);

        process(18L, "What is your standard delivery time?");
        verify(chatwoot).postMessage(eq(CONV), contains("Source: " + POLICY), eq(false));
    }

    @Test
    void conversationLockSerialisesOwnershipChanges() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        Thread holder = new Thread(() -> conversations.inLock(CONV, c -> {
            holding.countDown();
            sleep(800);
            return null;
        }));
        holder.start();
        holding.await(5, TimeUnit.SECONDS);

        long started = System.nanoTime();
        conversations.inLock(CONV, c -> null);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        holder.join();

        assertThat(waitedMs).as("second lock waited for the first").isGreaterThanOrEqualTo(500);
    }

    @Test
    void versionIncreasesOnEveryOwnershipChange() {
        conversations.current(CONV);
        ConversationControl before = conversations.current(CONV);
        conversations.inLock(CONV, c -> conversations.move(c, ConversationState.HANDOFF_PENDING));
        ConversationControl after = conversations.current(CONV);
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThatCode(() -> conversations.inLock(CONV, c -> conversations.move(c, ConversationState.AI_ACTIVE)))
                .doesNotThrowAnyException();
    }

    @Test
    void inboxClaimIsExclusiveAndOrdered() {
        inbox.enqueue(new ChatwootEvent(ChatwootEvent.Kind.CUSTOMER_MESSAGE, 4, 1, 40L, 401L, "a"), "x1");
        inbox.enqueue(new ChatwootEvent(ChatwootEvent.Kind.CUSTOMER_MESSAGE, 4, 1, 41L, 402L, "b"), "x2");
        var first = inbox.claim("t1", java.time.Duration.ofMinutes(2));
        var second = inbox.claim("t2", java.time.Duration.ofMinutes(2));
        var third = inbox.claim("t3", java.time.Duration.ofMinutes(2));
        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(second.get().id()).isNotEqualTo(first.get().id());
        assertThat(third).isEmpty();
    }

    // --- helpers -------------------------------------------------------------

    private void process(long messageId, String text) {
        inbox.enqueue(new ChatwootEvent(ChatwootEvent.Kind.CUSTOMER_MESSAGE, 4, 1, CONV, messageId, text), "delivery-" + messageId);
        worker.poll();
    }

    private String outcome(long messageId) {
        return jdbc.sql("select coalesce(outcome, last_error, status) from webhook_inbox where dedupe_key = :k")
                .param("k", "chatwoot:4:message_created:" + messageId).query(String.class).single();
    }

    private String executionState(long messageId) {
        return jdbc.sql("""
                select e.state from agent_execution e join webhook_inbox w on w.id = e.webhook_inbox_id
                where w.dedupe_key = :k
                """).param("k", "chatwoot:4:message_created:" + messageId).query(String.class).single();
    }

    private ConversationState state() {
        return ConversationState.valueOf(jdbc.sql("select state from conversation_control where chatwoot_conversation_id = :c")
                .param("c", CONV).query(String.class).single());
    }

    private static RagResult policyAnswer(double score) {
        return new RagResult("Standard delivery takes 3-5 business days after dispatch.",
                List.of(new RagResult.Chunk(score, POLICY, "1a3e2ef2-25e1-5fbf-af88-360ac28049a8", AnswerGroundingTest.CHUNK_TEXT)));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
