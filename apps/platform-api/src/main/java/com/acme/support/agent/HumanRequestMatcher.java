package com.acme.support.agent;

import java.util.regex.Pattern;

/** Deterministic human-request detection. Runs before any retrieval; an LLM is never the only check. */
public final class HumanRequestMatcher {

    private static final Pattern NOUN = Pattern.compile(
            "\\b(human|person|agent|representative|operator)s?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHRASE = Pattern.compile(
            "\\b(speak|talk|connect|chat)\\s+(me\\s+)?(to|with)\\s+(someone|somebody|a\\s+real|a\\s+live)\\b",
            Pattern.CASE_INSENSITIVE);

    private HumanRequestMatcher() {
    }

    public static boolean isHumanRequest(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return NOUN.matcher(text).find() || PHRASE.matcher(text).find();
    }
}
