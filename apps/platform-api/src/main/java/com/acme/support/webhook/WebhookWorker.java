package com.acme.support.webhook;

import com.acme.support.agent.AgentTurnService;
import com.acme.support.agent.SendOutcomeUnknownException;
import com.acme.support.conversation.ConversationService;
import com.acme.support.conversation.ConversationState;
import com.acme.support.shared.UpstreamException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Single-node worker: processes queued deliveries one at a time, which serialises turns per conversation.
 * Transient upstream failures are retried with bounded backoff; everything else ends FAILED (visible in admin).
 */
@Component
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
public class WebhookWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookWorker.class);
    private static final int BATCH = 20;

    private final WebhookInbox inbox;
    private final AgentTurnService turns;
    private final ConversationService conversations;
    private final String owner = "worker-" + UUID.randomUUID();
    private final Duration lease;
    private final Duration retryBase;
    private final int maxAttempts;

    public WebhookWorker(WebhookInbox inbox, AgentTurnService turns, ConversationService conversations,
                         @Value("${worker.lease}") Duration lease, @Value("${worker.retry-base}") Duration retryBase,
                         @Value("${worker.max-attempts}") int maxAttempts) {
        this.inbox = inbox;
        this.turns = turns;
        this.conversations = conversations;
        this.lease = lease;
        this.retryBase = retryBase;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${worker.poll-interval-ms}")
    public void poll() {
        for (int i = 0; i < BATCH; i++) {
            Optional<WebhookInbox.Claimed> claimed = inbox.claim(owner, lease);
            if (claimed.isEmpty()) {
                return;
            }
            process(claimed.get());
        }
    }

    void process(WebhookInbox.Claimed c) {
        MDC.put("webhook", c.id().toString());
        MDC.put("conversation", String.valueOf(c.conversationId()));
        try {
            String outcome = c.kind() == ChatwootEvent.Kind.HUMAN_REPLY
                    ? humanTakeover(c)
                    : turns.handleCustomerMessage(c.id(), c.conversationId(), c.messageId(), c.customerText());
            conversations.recordProcessed(c.conversationId(), c.messageId());
            inbox.done(c.id(), outcome);
            log.info("delivery processed outcome={} attempts={}", outcome, c.attempts());
        } catch (UpstreamException e) {
            if (e.isTransient() && c.attempts() < maxAttempts) {
                Duration delay = retryBase.multipliedBy(1L << Math.max(0, c.attempts() - 1));
                inbox.retryLater(c.id(), delay, e.getMessage());
                log.warn("delivery transient failure, retry in {} ms: {}", delay.toMillis(), e.getMessage());
            } else {
                inbox.failed(c.id(), e.getMessage());
                log.error("delivery failed terminally: {}", e.getMessage());
            }
        } catch (SendOutcomeUnknownException e) {
            inbox.failed(c.id(), e.getMessage());
            log.error("delivery failed closed: {}", e.getMessage());
        } catch (RuntimeException e) {
            // Only the class name is recorded: exception messages could echo customer text.
            inbox.failed(c.id(), "unexpected " + e.getClass().getSimpleName());
            log.error("delivery failed unexpectedly: {}", e.getClass().getName());
        } finally {
            MDC.clear();
        }
    }

    /** A human reply makes the human the owner. It never produces an AI turn. */
    private String humanTakeover(WebhookInbox.Claimed c) {
        return conversations.inLock(c.conversationId(), locked -> {
            if (locked.state() == ConversationState.AI_ACTIVE) {
                conversations.move(locked, ConversationState.HUMAN_ACTIVE);
                return "HUMAN_TOOK_OVER";
            }
            return "HUMAN_REPLY_RECORDED";
        });
    }
}
