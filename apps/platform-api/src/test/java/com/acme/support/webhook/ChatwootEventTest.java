package com.acme.support.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ChatwootEventTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void customerIncomingMessageTriggersTurn() throws IOException {
        ChatwootEvent e = parse("fixtures/agent-bot-message-created-incoming.json");
        assertThat(e.kind()).isEqualTo(ChatwootEvent.Kind.CUSTOMER_MESSAGE);
        assertThat(e.accountId()).isEqualTo(4);
        assertThat(e.inboxId()).isEqualTo(1);
        assertThat(e.conversationId()).isEqualTo(1);
        assertThat(e.messageId()).isEqualTo(7);
        assertThat(e.content()).isEqualTo("What is your standard delivery time?");
        assertThat(e.dedupeKey()).isEqualTo("chatwoot:4:message_created:7");
    }

    @Test
    void botReplyIsIgnored() throws IOException {
        assertThat(parse("fixtures/agent-bot-message-created-outgoing-bot.json").kind()).isEqualTo(ChatwootEvent.Kind.IGNORED);
    }

    @Test
    void humanPublicReplyMovesOwnershipButNeverTriggersAi() {
        JsonNode n = mapper.readTree("{\"event\":\"message_created\",\"message_type\":\"outgoing\",\"private\":false,"
                + "\"sender\":{\"type\":\"user\"},\"id\":9,\"content\":\"hi\",\"account\":{\"id\":4},\"inbox\":{\"id\":1},"
                + "\"conversation\":{\"id\":1}}");
        assertThat(ChatwootEvent.parse(n).kind()).isEqualTo(ChatwootEvent.Kind.HUMAN_REPLY);
    }

    @Test
    void privateNotesAndStatusEventsAreIgnored() {
        JsonNode note = mapper.readTree("{\"event\":\"message_created\",\"message_type\":\"outgoing\",\"private\":true,"
                + "\"sender\":{\"type\":\"user\"},\"id\":10,\"account\":{\"id\":4},\"inbox\":{\"id\":1},\"conversation\":{\"id\":1}}");
        JsonNode status = mapper.readTree("{\"event\":\"conversation_status_changed\",\"id\":11}");
        assertThat(ChatwootEvent.parse(note).kind()).isEqualTo(ChatwootEvent.Kind.IGNORED);
        assertThat(ChatwootEvent.parse(status).kind()).isEqualTo(ChatwootEvent.Kind.IGNORED);
    }

    @Test
    void widgetTemplateAndActivityMessagesAreNeverCustomerMessages() {
        // Live: "Give the team a way to reach you." is message_type 3 (template); "Conversation was reopened" is 2 (activity).
        JsonNode greeting = mapper.readTree("{\"event\":\"message_created\",\"message_type\":\"template\",\"private\":false,"
                + "\"id\":110,\"content\":\"Give the team a way to reach you.\",\"account\":{\"id\":4},\"inbox\":{\"id\":1},"
                + "\"conversation\":{\"id\":13}}");
        JsonNode numericTemplate = mapper.readTree("{\"event\":\"message_created\",\"message_type\":3,\"private\":false,"
                + "\"id\":111,\"account\":{\"id\":4},\"inbox\":{\"id\":1},\"conversation\":{\"id\":13}}");
        JsonNode activity = mapper.readTree("{\"event\":\"message_created\",\"message_type\":\"activity\",\"private\":false,"
                + "\"id\":112,\"account\":{\"id\":4},\"inbox\":{\"id\":1},\"conversation\":{\"id\":13}}");
        assertThat(ChatwootEvent.parse(greeting).kind()).isEqualTo(ChatwootEvent.Kind.IGNORED);
        assertThat(ChatwootEvent.parse(numericTemplate).kind()).isEqualTo(ChatwootEvent.Kind.IGNORED);
        assertThat(ChatwootEvent.parse(activity).kind()).isEqualTo(ChatwootEvent.Kind.IGNORED);
    }

    private ChatwootEvent parse(String path) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            return ChatwootEvent.parse(mapper.readTree(in.readAllBytes()));
        }
    }
}
