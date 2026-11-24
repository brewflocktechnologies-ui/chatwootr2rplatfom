package com.acme.support.conversation;

import static com.acme.support.conversation.ConversationState.AI_ACTIVE;
import static com.acme.support.conversation.ConversationState.HANDOFF_PENDING;
import static com.acme.support.conversation.ConversationState.HUMAN_ACTIVE;
import static com.acme.support.conversation.ConversationState.RESOLVED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationStateMachineTest {

    @Test
    void handoffPathIsAllowed() {
        assertThat(AI_ACTIVE.canMoveTo(HANDOFF_PENDING)).isTrue();
        assertThat(HANDOFF_PENDING.canMoveTo(HUMAN_ACTIVE)).isTrue();
    }

    @Test
    void humanReplyMayTakeOwnershipFromAi() {
        assertThat(AI_ACTIVE.canMoveTo(HUMAN_ACTIVE)).isTrue();
    }

    @Test
    void resumeIsOnlyFromHandoffOrHuman() {
        assertThat(HANDOFF_PENDING.canMoveTo(AI_ACTIVE)).isTrue();
        assertThat(HUMAN_ACTIVE.canMoveTo(AI_ACTIVE)).isTrue();
        assertThat(AI_ACTIVE.canMoveTo(AI_ACTIVE)).isTrue();
    }

    @Test
    void humanNeverReturnsToPendingOrResolvedWithoutAnAction() {
        assertThat(HUMAN_ACTIVE.canMoveTo(HANDOFF_PENDING)).isFalse();
        assertThat(HUMAN_ACTIVE.canMoveTo(RESOLVED)).isFalse();
    }

    @Test
    void resolvedHasNoMvpTransitions() {
        for (ConversationState to : ConversationState.values()) {
            if (to != RESOLVED) {
                assertThat(RESOLVED.canMoveTo(to)).isFalse();
            }
        }
    }
}
