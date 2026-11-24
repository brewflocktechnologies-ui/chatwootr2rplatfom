package com.acme.support.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnswerCoverageTest {

    private static final String EVIDENCE = "Standard orders are dispatched within 1-2 business days of being placed. "
            + "Standard delivery takes 3-5 business days after dispatch. "
            + "A return request may be submitted within 14 calendar days after delivery. "
            + "Approved refunds are processed within 5-7 business days after the returned item is received.";

    @Test
    void missingTermHandsOff() {
        assertThat(AnswerGrounding.covered("Do you sell gift cards?", EVIDENCE)).isFalse();
        assertThat(AnswerGrounding.uncoveredTerms("Do you sell gift cards?", EVIDENCE)).contains("gift", "card");
    }

    @Test
    void refundForUncoveredProductHandsOff() {
        assertThat(AnswerGrounding.covered("What is your refund policy for opened electronics?", EVIDENCE)).isFalse();
        assertThat(AnswerGrounding.uncoveredTerms("What is your refund policy for opened electronics?", EVIDENCE))
                .containsExactlyInAnyOrder("opened", "electronic");
    }

    @Test
    void addressChangeAfterDispatchHandsOff() {
        assertThat(AnswerGrounding.covered("Can I change my delivery address after dispatch?", EVIDENCE)).isFalse();
        assertThat(AnswerGrounding.uncoveredTerms("Can I change my delivery address after dispatch?", EVIDENCE))
                .containsExactlyInAnyOrder("change", "address");
    }

    @Test
    void stemMatchPasses() {
        // "return" in the question matches "returns" (or "return request") in the evidence.
        assertThat(AnswerGrounding.covered("What is your return policy?", "Returns are accepted within 14 days.")).isTrue();
        assertThat(AnswerGrounding.covered("What is your return policy?", EVIDENCE)).isTrue();
        assertThat(AnswerGrounding.covered("What is your standard delivery time?", EVIDENCE)).isTrue();
    }

    @Test
    void stopWordOnlyQuestionPassesThroughToNormalCheck() {
        assertThat(AnswerGrounding.uncoveredTerms("What is your policy?", "")).isEmpty();
        assertThat(AnswerGrounding.covered("Can you tell me?", "")).isTrue();
    }
}
