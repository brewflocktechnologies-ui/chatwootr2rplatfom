package com.acme.support.handoff;

import java.util.List;

/**
 * Builds the private handoff note. Contains intent, checks, genuine sources, reason and next step.
 * Never contains prompts, model reasoning, credentials or hidden policy text.
 */
public final class HandoffNote {

    private static final int EXCERPT_MAX = 200;

    private HandoffNote() {
    }

    public static String build(String handoffKey, HandoffReason reason, String intent, String customerExcerpt,
                               List<String> checks, List<String> sourceTitles) {
        String sources = sourceTitles.isEmpty() ? "none (no retrieved source was used)" : String.join("; ", sourceTitles);
        StringBuilder sb = new StringBuilder();
        sb.append("AI handoff note (private)\n");
        sb.append("Handoff ref: ").append(handoffKey).append('\n');
        sb.append("Intent: ").append(intent).append('\n');
        sb.append("Customer message excerpt: \"").append(excerpt(customerExcerpt)).append("\"\n");
        sb.append("Checked: ").append(String.join(" ", checks)).append('\n');
        sb.append("Knowledge sources retrieved: ").append(sources).append('\n');
        sb.append("Reason for handoff: ").append(reason.text()).append('\n');
        sb.append("Suggested next step: Review the customer's request and reply directly in this conversation.");
        return sb.toString();
    }

    static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= EXCERPT_MAX ? oneLine : oneLine.substring(0, EXCERPT_MAX) + "...";
    }
}
