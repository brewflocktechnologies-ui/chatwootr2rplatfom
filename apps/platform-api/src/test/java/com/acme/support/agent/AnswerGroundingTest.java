package com.acme.support.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.support.knowledge.RagResult.Chunk;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnswerGroundingTest {

    static final String TITLE = "Acme Demo Support Policy (FICTIONAL)";
    static final String DISPATCH = "Standard orders are dispatched within 1-2 business days of being placed.";
    static final String DELIVERY = "Standard delivery takes 3-5 business days after dispatch.";
    static final String ROUGH = "Customers therefore receive a standard order in roughly 4-7 business days from the day it is placed.";
    static final String RETURNS = "A return request may be submitted within 14 calendar days after delivery.";
    static final String REFUNDS = "Approved refunds are processed within 5-7 business days after the returned item is received and approved.";

    /** Shape of a real R2R chunk: title header, then the body with headings and markdown emphasis. */
    static final String CHUNK_TEXT = "Document Title: " + TITLE + "\n\nText: # Acme Demo — Customer Support Policy\n\n"
            + "## 1. Order dispatch\n\n- Standard orders are dispatched within **1–2 business days** of being placed.\n\n"
            + "## 2. Standard delivery\n\n- Standard delivery takes **3–5 business days after dispatch**.\n"
            + "- Customers therefore receive a standard order in roughly 4–7 business days from the day it is placed.\n\n"
            + "## 3. Returns\n\n- A return request may be submitted within **14 calendar days after delivery**.\n"
            + "- Only **unused items in their original packaging** are eligible for a return.\n\n"
            + "## 4. Refunds\n\n- Approved refunds are processed within **5–7 business days after the returned item is received and approved**.\n";

    static final Set<String> DELIVERY_Q = AnswerGrounding.questionStems("What is your standard delivery time?");
    static final Set<String> RETURN_Q = AnswerGrounding.questionStems("What is your return policy?");

    @Test
    void dashVariantsAndWhitespaceAreNormalised() {
        assertThat(AnswerGrounding.normalise("1–2  business—days\n\tok **x**")).isEqualTo("1-2 business-days ok x");
        assertThat(AnswerGrounding.normalise(null)).isEmpty();
    }

    @Test
    void headingsAndMarkdownAreNotEvidenceSentences() {
        List<String> sentences = AnswerGrounding.sentences(CHUNK_TEXT);
        assertThat(sentences).noneMatch(s -> s.startsWith("1. ") || s.startsWith("2. ") || s.startsWith("Document Title"));
        assertThat(sentences).contains(DISPATCH, DELIVERY, RETURNS);
    }

    @Test
    void deliveryQuestionSelectsOnlyDispatchAndDeliveryClauses() {
        List<String> required = AnswerGrounding.requiredSentences(List.of(new Chunk(0.65, TITLE, "d", CHUNK_TEXT)), 0.5,
                DELIVERY_Q);
        assertThat(required).containsExactly(DISPATCH, DELIVERY);
    }

    @Test
    void returnsSentenceIsExcludedFromTheDeliveryQuestion() {
        // Shares only the generic word "delivery" with the question; "return" is in no delivery question term.
        List<String> required = AnswerGrounding.requiredSentences(List.of(new Chunk(0.65, TITLE, "d", CHUNK_TEXT)), 0.5,
                DELIVERY_Q);
        assertThat(required).noneMatch(s -> s.startsWith("A return request"));
    }

    @Test
    void derivedFourToSevenSentenceIsExcludedFromTheDeliveryQuestion() {
        // Shares only the generic word "standard" with the question, so it does not qualify on its own.
        List<String> required = AnswerGrounding.requiredSentences(List.of(new Chunk(0.65, TITLE, "d", CHUNK_TEXT)), 0.5,
                DELIVERY_Q);
        assertThat(required).noneMatch(s -> s.contains("4-7"));
        assertThat(required).contains(DISPATCH);
    }

    @Test
    void dispatchSentenceIsKeptAsThePrerequisiteOfTheDeliverySentence() {
        // The delivery sentence says "after dispatch"; the dispatch sentence matches that word by prefix ("dispatched").
        assertThat(AnswerGrounding.objectOfAfter(DELIVERY)).isEqualTo("dispatch");
        List<String> required = AnswerGrounding.requiredSentences(List.of(new Chunk(0.65, TITLE, "d", CHUNK_TEXT)), 0.5,
                DELIVERY_Q);
        assertThat(required).contains(DISPATCH);
    }

    @Test
    void sentenceRepeatedAcrossOverlappingChunksIsNotMistakenForSharedTerm() {
        // Live: one chunk held the whole policy and another held the returns section. The returns sentence
        // must count once, so the return term still identifies it.
        List<Chunk> overlapping = List.of(new Chunk(0.623, TITLE, "d1", CHUNK_TEXT),
                new Chunk(0.697, TITLE, "d2", "Document Title: " + TITLE + "\n\nText: 3. Returns\n\n"
                        + "A return request may be submitted within **14 calendar days after delivery**.\n"));
        assertThat(AnswerGrounding.requiredSentences(overlapping, 0.5,
                AnswerGrounding.questionStems("What is your return policy?"))).containsExactly(RETURNS);
    }

    @Test
    void returnsQuestionSelectsOnlyTheReturnsSentenceAndNoDeliveryText() {
        // The returns sentence says "after delivery", but it qualified on a distinctive term, so it does not expand.
        List<String> required = AnswerGrounding.requiredSentences(List.of(new Chunk(0.70, TITLE, "d", CHUNK_TEXT)), 0.5,
                AnswerGrounding.questionStems("What is your return policy?"));
        assertThat(required).containsExactly(RETURNS);
    }

    @Test
    void returnsQuestionDoesNotPullDeliveryOrDispatchClauses() {
        List<String> required = AnswerGrounding.requiredSentences(List.of(new Chunk(0.70, TITLE, "d", CHUNK_TEXT)), 0.5,
                RETURN_Q);
        assertThat(required).containsExactly(RETURNS);
        assertThat(required).noneMatch(s -> s.contains("dispatch") || s.contains("3-5") || s.contains("1-2"));
    }

    @Test
    void belowThresholdChunksAreIgnored() {
        assertThat(AnswerGrounding.requiredSentences(List.of(new Chunk(0.30, TITLE, "d", CHUNK_TEXT)), 0.5, DELIVERY_Q))
                .isEmpty();
    }

    @Test
    void passesOnlyWhenEveryRequiredSentenceAppearsAndNoFigureIsInvented() {
        List<String> required = List.of(DISPATCH, DELIVERY);
        String evidence = CHUNK_TEXT;
        assertThat(AnswerGrounding.passes(DISPATCH + " " + DELIVERY, required, evidence)).isTrue();
        // Dash variant in the answer still matches the en-dash source.
        assertThat(AnswerGrounding.passes("Standard orders are dispatched within 1–2 business days of being placed. "
                + DELIVERY, required, evidence)).isTrue();
        assertThat(AnswerGrounding.passes(DELIVERY, required, evidence)).isFalse();
        assertThat(AnswerGrounding.passes(DISPATCH + " " + DELIVERY + " Delivery takes 9 days.", required, evidence))
                .as("figure 9 is not in the evidence").isFalse();
    }

    @Test
    void missingReportsOnlyAbsentSentences() {
        assertThat(AnswerGrounding.missing(DELIVERY, List.of(DISPATCH, DELIVERY))).containsExactly(DISPATCH);
    }

    @Test
    void extraDigitSentenceFailsEvenWhenRequiredOnesArePresent() {
        List<String> required = List.of(DISPATCH, DELIVERY);
        String extra = DISPATCH + " " + DELIVERY + " Returns take 14 days after delivery.";
        assertThat(AnswerGrounding.passes(extra, required, CHUNK_TEXT)).isFalse();
    }

    @Test
    void textAfterSourceLineIsTrimmed() {
        String answer = DELIVERY + "\n\nSource: " + TITLE + "\n\nNote: model commentary.";
        assertThat(AnswerGrounding.trimAfterSource(answer)).isEqualTo(DELIVERY + "\n\nSource: " + TITLE);
        assertThat(AnswerGrounding.trimAfterSource("no source here")).isEqualTo("no source here");
    }

    @Test
    void refusalIsRecognisedExactlyAfterNormalisation() {
        assertThat(AnswerGrounding.isRefusal("I cannot find that in our policy.")).isTrue();
        assertThat(AnswerGrounding.isRefusal("  I cannot find that in our policy.  ")).isTrue();
        assertThat(AnswerGrounding.isRefusal("I cannot find that in our policy. Try again.")).isFalse();
    }
}
