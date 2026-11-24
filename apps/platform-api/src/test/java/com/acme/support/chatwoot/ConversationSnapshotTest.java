package com.acme.support.chatwoot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationSnapshotTest {

    private static final long OWN_BOT = 1L;

    @Test
    void pendingBotAssignedToOurAgentBotIsEligible() {
        // Observed on widget conversations: Chatwoot sets pending and assigns the AgentBot.
        assertThat(new ConversationSnapshot(1, "pending", OWN_BOT).aiEligible(OWN_BOT)).isTrue();
    }

    @Test
    void openUnassignedIsEligible() {
        assertThat(new ConversationSnapshot(1, "open", null).aiEligible(OWN_BOT)).isTrue();
        assertThat(new ConversationSnapshot(1, "pending", null).aiEligible(OWN_BOT)).isTrue();
    }

    @Test
    void humanAssignedIsNotEligible() {
        assertThat(new ConversationSnapshot(1, "open", 42L).aiEligible(OWN_BOT)).isFalse();
        assertThat(new ConversationSnapshot(1, "pending", 42L).aiEligible(OWN_BOT)).isFalse();
    }

    @Test
    void otherBotAssignedIsNotEligible() {
        assertThat(new ConversationSnapshot(1, "pending", 7L).aiEligible(OWN_BOT)).isFalse();
    }

    @Test
    void resolvedOrSnoozedStatusIsNotEligible() {
        assertThat(new ConversationSnapshot(1, "resolved", OWN_BOT).aiEligible(OWN_BOT)).isFalse();
        assertThat(new ConversationSnapshot(1, "snoozed", null).aiEligible(OWN_BOT)).isFalse();
    }
}
