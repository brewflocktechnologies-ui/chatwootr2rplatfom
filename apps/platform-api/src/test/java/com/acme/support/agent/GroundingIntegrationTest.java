package com.acme.support.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.support.AbstractIntegrationTest;
import com.acme.support.chatwoot.ChatwootClient;
import com.acme.support.chatwoot.ConversationSnapshot;
import com.acme.support.knowledge.R2rClient;
import com.acme.support.knowledge.RagResult;
import com.acme.support.knowledge.RagResult.Chunk;
import com.acme.support.webhook.ChatwootEvent;
import com.acme.support.webhook.WebhookInbox;
import com.acme.support.webhook.WebhookWorker;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Grounding branches: MATCHED, REGENERATED, FALLBACK_QUOTED, refusal handoff. Chatwoot and R2R are mocked. */
// Same mock set as AgentTurnIntegrationTest, so it would reuse that cached context and its stale container URL.
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class GroundingIntegrationTest extends AbstractIntegrationTest {

    private static final long CONV = 1L;
    private static final String TITLE = AnswerGroundingTest.TITLE;
    private static final String DISPATCH = AnswerGroundingTest.DISPATCH;
    private static final String DELIVERY = AnswerGroundingTest.DELIVERY;
    private static final String ROUGH = AnswerGroundingTest.ROUGH;
    /** All four digit sentences the question shares a word with, in document order. */
    private static final String RETURNS = AnswerGroundingTest.RETURNS;
    private static final String FULL = DISPATCH + " " + DELIVERY;

    @MockitoBean
    ChatwootClient chatwoot;

    @MockitoBean
    R2rClient r2r;

    @Autowired
    WebhookInbox inbox;

    @Autowired
    WebhookWorker worker;

    @Autowired
    com.acme.support.webhook.WebhookIntake intake;

    @BeforeEach
    void setUp() {
        resetPlatformState();
        org.mockito.Mockito.reset(chatwoot, r2r);
        when(chatwoot.getConversation(anyLong())).thenReturn(new ConversationSnapshot(CONV, "pending", 1L));
        when(chatwoot.postMessage(anyLong(), anyString(), anyBoolean())).thenReturn(555L);
        when(chatwoot.agentBotId()).thenReturn(1L);
    }

    @Test
    void completeAnswerIsSentWithoutRegeneration() {
        answersReturn(FULL + "\n\nSource: " + TITLE);

        process(30L);

        verify(r2r, times(1)).rag(anyString(), anyInt(), anyString());
        verify(chatwoot).postMessage(eq(CONV), eq(FULL + "\n\nSource: " + TITLE), eq(false));
        assertThat(outcome(30L)).isEqualTo("SENT");
    }

    @Test
    void missingSentenceIsFixedByOneRegeneration() {
        when(r2r.rag(anyString(), anyInt(), anyString()))
                .thenReturn(policy(DELIVERY))
                .thenReturn(policy(FULL));

        process(31L);

        verify(r2r, times(2)).rag(anyString(), anyInt(), anyString());
        verify(chatwoot).postMessage(eq(CONV), contains(DISPATCH), eq(false));
        assertThat(outcome(31L)).isEqualTo("SENT");
    }

    @Test
    void stillMissingAfterRegenerationQuotesTheMissingEvidenceVerbatim() {
        answersReturn(DELIVERY);

        process(32L);

        verify(r2r, times(2)).rag(anyString(), anyInt(), anyString());
        verify(chatwoot).postMessage(eq(CONV), eq(DISPATCH + "\n\nSource: " + TITLE), eq(false));
        assertThat(outcome(32L)).isEqualTo("SENT");
    }

    @Test
    void inventedFigureNeverReachesTheCustomerAsModelText() {
        answersReturn(FULL + " It also takes 9 days.");

        process(33L);

        verify(chatwoot, never()).postMessage(anyLong(), contains("9 days"), anyBoolean());
        verify(chatwoot).postMessage(eq(CONV), eq(FULL + "\n\nSource: " + TITLE), eq(false));
    }

    @Test
    void modelRefusalHandsOffWithNoCustomerAnswer() {
        answersReturn("I cannot find that in our policy.");

        process(34L);

        verify(r2r, times(1)).rag(anyString(), anyInt(), anyString());
        verify(chatwoot, never()).postMessage(anyLong(), anyString(), eq(false));
        verify(chatwoot).postMessage(eq(CONV), contains("Handoff ref"), eq(true));
        assertThat(outcome(34L)).isEqualTo("HANDED_OFF");
    }

    @Test
    void offPolicyQuestionHandsOffBeforeAnyAnswerIsUsed() {
        answersReturn(FULL + "\n\nSource: " + TITLE);

        processText(35L, "Do you sell gift cards?");

        verify(chatwoot, never()).postMessage(anyLong(), contains("Source:"), eq(false));
        verify(chatwoot).postMessage(eq(CONV), contains("Handoff ref"), eq(true));
        assertThat(outcome(35L)).isEqualTo("HANDED_OFF");
    }

    @Test
    void humanRequestDuringInFlightTurnGetsNoAiAnswerAndOneHandoffNote() {
        // The RAG call is the in-flight window: the human request is accepted at intake while the turn waits.
        when(r2r.rag(anyString(), anyInt(), anyString())).thenAnswer(invocation -> {
            intake.accept(new ChatwootEvent(ChatwootEvent.Kind.CUSTOMER_MESSAGE, 4, 1, CONV, 51L,
                    "I need to speak to a human agent, please."), "delivery-51");
            return policy(FULL);
        });
        inbox.enqueue(new ChatwootEvent(ChatwootEvent.Kind.CUSTOMER_MESSAGE, 4, 1, CONV, 50L,
                "What is your standard delivery time?"), "delivery-50");

        worker.poll();

        // The in-flight turn's send guard sees HANDOFF_PENDING under the lock and discards its late answer.
        verify(chatwoot, never()).postMessage(anyLong(), anyString(), eq(false));
        assertThat(outcome(50)).isEqualTo("DISCARDED");
        // The queued human-request turn drives the one handoff: exactly one private note, then HUMAN_ACTIVE.
        verify(chatwoot, times(1)).postMessage(eq(CONV), contains("Handoff ref"), eq(true));
        assertThat(outcome(51)).isEqualTo("HANDOFF_COMPLETED");
        assertThat(jdbc.sql("select state from conversation_control where chatwoot_conversation_id = 1")
                .query(String.class).single()).isEqualTo("HUMAN_ACTIVE");
    }

    @Test
    void normalQuestionAtIntakeLeavesTheConversationAiActiveAndRunsTheTurnUnchanged() {
        answersReturn(FULL + "\n\nSource: " + TITLE);
        intake.accept(new ChatwootEvent(ChatwootEvent.Kind.CUSTOMER_MESSAGE, 4, 1, CONV, 60L,
                "What is your standard delivery time?"), "delivery-60");
        assertThat(jdbc.sql("select count(*) from conversation_control where chatwoot_conversation_id = 1")
                .query(Long.class).single()).as("intake alone does not change ownership").isZero();

        worker.poll();

        verify(chatwoot).postMessage(eq(CONV), eq(FULL + "\n\nSource: " + TITLE), eq(false));
        assertThat(outcome(60)).isEqualTo("SENT");
        assertThat(jdbc.sql("select state from conversation_control where chatwoot_conversation_id = 1")
                .query(String.class).single()).isEqualTo("AI_ACTIVE");
    }

    // --- helpers -------------------------------------------------------------

    private void answersReturn(String answer) {
        when(r2r.rag(anyString(), anyInt(), anyString())).thenReturn(policy(answer));
    }

    private static RagResult policy(String answer) {
        return new RagResult(answer, List.of(new Chunk(0.649, TITLE, "1a3e2ef2-25e1-5fbf-af88-360ac28049a8",
                AnswerGroundingTest.CHUNK_TEXT)));
    }

    private void processText(long messageId, String text) {
        inbox.enqueue(new ChatwootEvent(ChatwootEvent.Kind.CUSTOMER_MESSAGE, 4, 1, CONV, messageId, text),
                "delivery-" + messageId);
        worker.poll();
    }

    private void process(long messageId) {
        inbox.enqueue(new ChatwootEvent(ChatwootEvent.Kind.CUSTOMER_MESSAGE, 4, 1, CONV, messageId,
                "What is your standard delivery time?"), "delivery-" + messageId);
        worker.poll();
    }

    private String outcome(long messageId) {
        return jdbc.sql("select coalesce(outcome, last_error, status) from webhook_inbox where dedupe_key = :k")
                .param("k", "chatwoot:4:message_created:" + messageId).query(String.class).single();
    }
}
