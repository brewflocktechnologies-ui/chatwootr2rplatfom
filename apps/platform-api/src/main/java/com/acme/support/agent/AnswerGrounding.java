package com.acme.support.agent;

import com.acme.support.knowledge.RagResult.Chunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic grounding check for generated answers.
 *
 * Required sentences are evidence sentences that contain a digit and share a content word with the question.
 * An answer passes when every required sentence appears in it (after dash and whitespace normalisation) and
 * every figure in the answer also appears in the evidence. Relevance is lexical, so it is a deliberate
 * approximation: it keeps unrelated clauses (returns, refunds) out of a delivery answer.
 */
public final class AnswerGrounding {

    public static final String REFUSAL = "I cannot find that in our policy.";

    /** Outcome codes only. Never log the text. */
    public enum Outcome { MATCHED, REGENERATED, FALLBACK_QUOTED }

    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern FIGURE = Pattern.compile("(?<![\\d.])\\d+(?:[.,]\\d+)*(?![\\d])");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\d+\\.\\s.*");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    private static final Set<String> STOP = Set.of("what", "is", "are", "your", "the", "a", "an", "and", "or", "for",
            "of", "to", "in", "on", "at", "do", "does", "how", "can", "i", "you", "we", "my", "it", "with", "from",
            "that", "this", "any", "about", "have", "has", "much", "many", "when", "which", "who", "where", "there",
            "be", "will", "would", "should", "tell", "me", "please", "get", "our");

    private AnswerGrounding() {
    }

    /** Dash variants to hyphen, markdown emphasis removed, whitespace collapsed. Applied to both sides of a check. */
    public static String normalise(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('–', '-').replace('—', '-').replace("*", "").replace("_", "")
                .replaceAll("\\s+", " ").trim();
    }

    /** Customer-facing text: dash variants to hyphen and emphasis removed; line breaks kept. */
    public static String displayText(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\u2013', '-').replace('\u2014', '-').replace("*", "").replace("_", "").strip();
    }

    public static boolean isRefusal(String answer) {
        return normalise(answer).equals(REFUSAL);
    }

    public static Set<String> questionStems(String question) {
        Set<String> out = new LinkedHashSet<>();
        for (String tok : tokens(question)) {
            if (!STOP.contains(tok)) {
                out.add(stem(tok));
            }
        }
        return out;
    }

    /**
     * Required evidence sentences for a question. Only digit-bearing sentences from relevant chunks are candidates.
     *
     * Rule (derived from the policy, not hard-coded to it):
     *  1. Distinctive match: the sentence contains a question term that occurs in no other candidate sentence.
     *  2. Two-term match: the sentence contains two or more question terms.
     *  A sentence that matches on a single term shared with other candidates ("delivery", "standard") does not
     *  qualify on its own.
     *  3. Prerequisite exception (only for sentences that qualified by rule 2): the first content word after
     *  "after" in such a sentence names a prior step, and candidates containing that word (prefix match, so
     *  "dispatch" matches "dispatched") are also required. Rule 1 matches never expand, so a returns sentence
     *  that says "after delivery" cannot pull in delivery text.
     */
    public static List<String> requiredSentences(List<Chunk> chunks, double threshold, Set<String> questionStems) {
        // Chunks can overlap (R2R returned a full-policy chunk and a section chunk), so dedupe sentences first;
        // otherwise a sentence seen twice would look shared and stop being distinctive.
        Set<String> candidateSet = new LinkedHashSet<>();
        for (Chunk c : chunks) {
            if (c.score() < threshold) {
                continue;
            }
            for (String sentence : sentences(c.text())) {
                if (DIGIT.matcher(sentence).find()) {
                    candidateSet.add(sentence);
                }
            }
        }
        List<String> candidates = new ArrayList<>(candidateSet);
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (String sentence : candidates) {
            for (String q : questionStems) {
                if (sentenceStems(sentence).contains(q)) {
                    documentFrequency.merge(q, 1, Integer::sum);
                }
            }
        }
        Set<String> selected = new LinkedHashSet<>();
        Set<String> twoTermMatches = new LinkedHashSet<>();
        for (String sentence : candidates) {
            Set<String> hits = new LinkedHashSet<>();
            for (String stem : sentenceStems(sentence)) {
                if (questionStems.contains(stem)) {
                    hits.add(stem);
                }
            }
            boolean distinctive = hits.stream().anyMatch(h -> documentFrequency.getOrDefault(h, 0) == 1);
            if (distinctive) {
                selected.add(sentence);
            } else if (hits.size() >= 2) {
                selected.add(sentence);
                twoTermMatches.add(sentence);
            }
        }
        for (String anchor : twoTermMatches) {
            String prerequisite = objectOfAfter(anchor);
            if (prerequisite.isEmpty()) {
                continue;
            }
            for (String candidate : candidates) {
                if (hasPrefixToken(candidate, prerequisite)) {
                    selected.add(candidate);
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (selected.contains(candidate)) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** First content word after "after" in a sentence, or empty. */
    static String objectOfAfter(String sentence) {
        String lower = normalise(sentence).toLowerCase(Locale.ROOT);
        int idx = lower.indexOf(" after ");
        if (idx < 0) {
            return "";
        }
        for (String tok : lower.substring(idx + " after ".length()).split("[^a-z]+")) {
            if (tok.length() >= 3 && !STOP.contains(tok)) {
                return tok;
            }
        }
        return "";
    }

    static boolean hasPrefixToken(String sentence, String prefix) {
        for (String tok : tokens(sentence)) {
            if (tok.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** All evidence text used for figure checks: relevant chunks only, normalised. */
    public static String evidenceText(List<Chunk> chunks, double threshold) {
        StringBuilder sb = new StringBuilder();
        for (Chunk c : chunks) {
            if (c.score() >= threshold) {
                sb.append(' ').append(normalise(bodyOf(c.text())));
            }
        }
        return sb.toString();
    }

    /** Required sentences that the answer does not contain. */
    public static List<String> missing(String answer, List<String> required) {
        String a = normalise(answer);
        List<String> out = new ArrayList<>();
        for (String r : required) {
            if (!a.contains(normalise(r))) {
                out.add(normalise(r));
            }
        }
        return out;
    }

    /** True when every required sentence is present and every figure in the answer is in the evidence. */
    public static boolean passes(String answer, List<String> required, String evidence) {
        if (!missing(answer, required).isEmpty()) {
            return false;
        }
        // Every digit-bearing sentence in the answer must be a required evidence sentence: no extra clauses.
        Set<String> allowed = new LinkedHashSet<>();
        for (String r : required) {
            allowed.add(normalise(r));
        }
        for (String piece : SENTENCE_END.split(normalise(answer))) {
            String n = normalise(piece);
            if (DIGIT.matcher(n).find() && !allowed.contains(n)) {
                return false;
            }
        }
        String ev = normalise(evidence);
        Matcher m = FIGURE.matcher(normalise(answer));
        while (m.find()) {
            if (!containsFigure(ev, m.group())) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsFigure(String haystack, String figure) {
        Matcher m = Pattern.compile("(?<![\\d.])" + Pattern.quote(figure) + "(?![\\d])").matcher(haystack);
        return m.find();
    }

    /**
     * Coverage stop list: generic question words that carry no policy content. Documented here, not configurable.
     * Every other word of three or more letters in the question must occur in the cited evidence.
     */
    static final Set<String> COVERAGE_STOP = Set.of("what", "is", "are", "was", "were", "your", "our", "the", "a", "an",
            "for", "of", "in", "on", "at", "to", "by", "do", "does", "did", "you", "we", "can", "i", "me", "my", "how",
            "when", "where", "which", "who", "policy", "policies", "time", "times", "please", "tell", "get", "have",
            "has", "will", "would", "should", "and", "or", "any", "there", "be", "it", "this", "that", "with", "from",
            "after", "before");

    /** Question content terms (stems) that do not occur in the evidence. Empty means the question is covered. */
    public static List<String> uncoveredTerms(String question, String evidence) {
        Set<String> evidenceStems = new LinkedHashSet<>();
        for (String tok : tokens(evidence)) {
            evidenceStems.add(stem(tok));
        }
        Set<String> missing = new LinkedHashSet<>();
        for (String tok : tokens(question)) {
            if (!COVERAGE_STOP.contains(tok) && !evidenceStems.contains(stem(tok))) {
                missing.add(stem(tok));
            }
        }
        return new ArrayList<>(missing);
    }

    public static boolean covered(String question, String evidence) {
        return uncoveredTerms(question, evidence).isEmpty();
    }

    /** Drops any model text after the line that carries "Source:". Keeps the answer and its source line only. */
    public static String trimAfterSource(String answer) {
        if (answer == null) {
            return "";
        }
        int idx = answer.indexOf("Source:");
        if (idx < 0) {
            return answer.strip();
        }
        int end = answer.indexOf('\n', idx);
        return (end < 0 ? answer : answer.substring(0, end)).strip();
    }

    /** Text after the "Text:" marker that R2R prefixes to chunk bodies. */
    static String bodyOf(String chunkText) {
        if (chunkText == null) {
            return "";
        }
        int i = chunkText.indexOf("Text:");
        return i >= 0 ? chunkText.substring(i + "Text:".length()) : chunkText;
    }

    /** Sentences from a chunk body. Numbered headings such as "2. Standard delivery" are not evidence sentences. */
    static List<String> sentences(String chunkText) {
        List<String> out = new ArrayList<>();
        for (String line : bodyOf(chunkText).split("\n")) {
            String l = line.replaceFirst("^[\\s#>*-]+", "").trim();
            if (l.isEmpty() || NUMBERED_HEADING.matcher(l).matches()) {
                continue;
            }
            for (String s : SENTENCE_END.split(l)) {
                String n = normalise(s);
                if (!n.isEmpty()) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    private static Set<String> sentenceStems(String sentence) {
        Set<String> out = new LinkedHashSet<>();
        for (String tok : tokens(sentence)) {
            if (!STOP.contains(tok)) {
                out.add(stem(tok));
            }
        }
        return out;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String s : a) {
            if (b.contains(s)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (t.length() >= 3) {
                out.add(t);
            }
        }
        return out;
    }

    /** Minimal plural stemmer: "refunds" to "refund", "returns" to "return". Good enough for lexical matching. */
    static String stem(String t) {
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) {
            return t.substring(0, t.length() - 1);
        }
        return t;
    }

}
