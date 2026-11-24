package com.acme.support.webhook;

import com.acme.support.agent.HumanRequestMatcher;
import com.acme.support.handoff.HandoffReason;
import com.acme.support.handoff.HandoffService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable intake for one delivery. A customer message that asks for a human moves an AI-active conversation to
 * HANDOFF_PENDING in the same transaction that queues it. An in-flight AI turn then fails its send guard (it
 * re-reads state under the row lock) and discards its late answer. The handoff note is written later, once, by
 * the queued turn through the idempotent handoff path.
 */
@Service
public class WebhookIntake {

    private final WebhookInbox inbox;
    private final HandoffService handoff;
    private final TransactionTemplate tx;

    public WebhookIntake(WebhookInbox inbox, HandoffService handoff, TransactionTemplate tx) {
        this.inbox = inbox;
        this.handoff = handoff;
        this.tx = tx;
    }

    /** True when the delivery was newly queued; false for a duplicate. */
    public boolean accept(ChatwootEvent event, String deliveryId) {
        return tx.execute(status -> {
            boolean queued = inbox.enqueue(event, deliveryId);
            if (queued && event.kind() == ChatwootEvent.Kind.CUSTOMER_MESSAGE
                    && HumanRequestMatcher.isHumanRequest(event.content())) {
                // Joins this transaction. No-op when a human already owns the conversation.
                handoff.open(event.conversationId(), HandoffReason.CUSTOMER_REQUESTED_HUMAN,
                        "Customer asked to speak with a person.", event.content(),
                        List.of("Deterministic human-request phrase matched at intake; knowledge base not searched."),
                        List.of());
            }
            return queued;
        });
    }
}
