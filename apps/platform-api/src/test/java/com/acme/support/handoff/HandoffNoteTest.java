package com.acme.support.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HandoffNoteTest {

    @Test
    void noteCarriesRefReasonSourcesAndNextStep() {
        String note = HandoffNote.build("acme-demo/conversation-1/generation-0", HandoffReason.CUSTOMER_REQUESTED_HUMAN,
                "Customer asked to speak with a person.", "I need to speak to a human agent, please.",
                List.of("Deterministic human-request phrase matched."), List.of("Acme Demo Support Policy (FICTIONAL)"));
        assertThat(note).contains("Handoff ref: acme-demo/conversation-1/generation-0")
                .contains("Reason for handoff: The customer asked for a human agent.")
                .contains("Knowledge sources retrieved: Acme Demo Support Policy (FICTIONAL)")
                .contains("Suggested next step:");
    }

    @Test
    void noSourcesIsStatedExplicitly() {
        String note = HandoffNote.build("k", HandoffReason.NO_EVIDENCE, "i", "q", List.of("c"), List.of());
        assertThat(note).contains("Knowledge sources retrieved: none (no retrieved source was used)");
    }

    @Test
    void excerptIsOneLineAndBounded() {
        String excerpt = HandoffNote.excerpt("line one\nline two " + "x".repeat(500));
        assertThat(excerpt).doesNotContain("\n").endsWith("...");
        assertThat(excerpt.length()).isLessThanOrEqualTo(203);
    }
}
