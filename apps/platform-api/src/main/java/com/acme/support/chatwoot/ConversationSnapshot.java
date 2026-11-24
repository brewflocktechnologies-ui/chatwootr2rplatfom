package com.acme.support.chatwoot;

/** Only the fields the platform needs to decide AI eligibility. assigneeId is null when unassigned. */
public record ConversationSnapshot(long id, String status, Long assigneeId) {

    /**
     * Final Chatwoot-side barrier before an AI send. Local ownership remains authoritative.
     * Eligible: status pending or open, and unassigned or assigned to our own AgentBot.
     * Any human assignee, or any other bot, makes the conversation ineligible.
     */
    public boolean aiEligible(long ownAgentBotId) {
        boolean statusOk = "pending".equals(status) || "open".equals(status);
        boolean assigneeOk = assigneeId == null || assigneeId == ownAgentBotId;
        return statusOk && assigneeOk;
    }
}
