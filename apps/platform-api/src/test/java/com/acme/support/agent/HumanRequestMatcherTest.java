package com.acme.support.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HumanRequestMatcherTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "I need to speak to a human agent, please.",
            "can I talk to a person",
            "Get me a representative",
            "Please connect me with someone real",
            "let me speak to somebody",
            "Is there a live agent?",
            "HUMAN"
    })
    void detectsExplicitHumanRequests(String text) {
        assertThat(HumanRequestMatcher.isHumanRequest(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What is your standard delivery time?",
            "Can you also check this for me?",
            "Do you ship to Mars?",
            "",
            "   "
    })
    void ignoresOrdinaryQuestions(String text) {
        assertThat(HumanRequestMatcher.isHumanRequest(text)).isFalse();
    }

    @Test
    void handlesNull() {
        assertThat(HumanRequestMatcher.isHumanRequest(null)).isFalse();
    }
}
