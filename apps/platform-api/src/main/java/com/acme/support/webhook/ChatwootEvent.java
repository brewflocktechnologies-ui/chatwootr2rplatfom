package com.acme.support.webhook;

import tools.jackson.databind.JsonNode;

/**
 * Classified AgentBot delivery. Only message_created is interpreted.
 * CUSTOMER_MESSAGE: message_type incoming, not private (drives the AI turn).
 * HUMAN_REPLY: outgoing, not private, sender type "user" (moves ownership to the human; never triggers AI).
 * Everything else (bot replies, private notes, status events) is IGNORED.
 */
public record ChatwootEvent(Kind kind, long accountId, long inboxId, long conversationId, long messageId, String content) {

    public enum Kind { CUSTOMER_MESSAGE, HUMAN_REPLY, IGNORED }

    public static ChatwootEvent parse(JsonNode n) {
        long account = n.path("account").path("id").asLong(-1);
        long inbox = n.path("inbox").path("id").asLong(-1);
        long conversation = n.path("conversation").path("id").asLong(-1);
        long message = n.path("id").asLong(-1);
        String content = n.path("content").asString("");
        if (!"message_created".equals(n.path("event").asString(""))) {
            return ignored(account, inbox, conversation, message, content);
        }
        boolean privateFlag = n.path("private").asBoolean(false);
        String messageType = n.path("message_type").asString("");
        String senderType = n.path("sender").path("type").asString("");
        Kind kind;
        if ("incoming".equals(messageType) && !privateFlag) {
            kind = Kind.CUSTOMER_MESSAGE;
        } else if ("outgoing".equals(messageType) && !privateFlag && "user".equals(senderType)) {
            kind = Kind.HUMAN_REPLY;
        } else {
            kind = Kind.IGNORED;
        }
        return new ChatwootEvent(kind, account, inbox, conversation, message, content);
    }

    private static ChatwootEvent ignored(long a, long i, long c, long m, String content) {
        return new ChatwootEvent(Kind.IGNORED, a, i, c, m, content);
    }

    public String dedupeKey() {
        return "chatwoot:" + accountId + ":message_created:" + messageId;
    }
}
