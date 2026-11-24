package com.acme.support.handoff;

public enum HandoffReason {
    CUSTOMER_REQUESTED_HUMAN("The customer asked for a human agent."),
    NO_EVIDENCE("The knowledge base returned no evidence above the threshold, so the AI did not answer."),
    KNOWLEDGE_UNAVAILABLE("The knowledge service failed after bounded retries, so the AI did not answer."),
    NO_USABLE_ANSWER("The knowledge service returned no usable answer text, so the AI did not answer."),
    SEND_OUTCOME_UNKNOWN("An AI reply may or may not have been delivered to the customer, so the AI will not retry the send.");

    private final String text;

    HandoffReason(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }
}
