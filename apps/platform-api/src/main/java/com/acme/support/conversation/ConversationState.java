package com.acme.support.conversation;

public enum ConversationState {
    AI_ACTIVE,
    HANDOFF_PENDING,
    HUMAN_ACTIVE,
    /** Reserved. No MVP path enters or leaves this state. */
    RESOLVED;

    /** Allowed transitions. Same-state is always a no-op and allowed. */
    public boolean canMoveTo(ConversationState to) {
        if (this == to) {
            return true;
        }
        return switch (this) {
            case AI_ACTIVE -> to == HANDOFF_PENDING || to == HUMAN_ACTIVE;
            case HANDOFF_PENDING -> to == HUMAN_ACTIVE || to == AI_ACTIVE;
            case HUMAN_ACTIVE -> to == AI_ACTIVE;
            case RESOLVED -> false;
        };
    }
}
