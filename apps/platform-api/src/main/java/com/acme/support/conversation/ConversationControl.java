package com.acme.support.conversation;

/** Platform-owned ownership record for one Chatwoot conversation. */
public record ConversationControl(long chatwootConversationId, ConversationState state, long version, int handoffGeneration) {
}
