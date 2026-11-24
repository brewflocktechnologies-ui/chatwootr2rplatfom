package com.acme.support.agent;

import com.acme.support.knowledge.RagResult.Chunk;
import java.util.List;

/** Evidence gate. A chunk qualifies only with a score at or above the threshold and a real document title. */
public final class EvidencePolicy {

    private EvidencePolicy() {
    }

    public static boolean hasEvidence(List<Chunk> chunks, double threshold) {
        return chunks.stream().anyMatch(c -> c.score() >= threshold && !c.documentTitle().isBlank());
    }

    public static List<String> sourceTitles(List<Chunk> chunks, double threshold) {
        return chunks.stream()
                .filter(c -> c.score() >= threshold && !c.documentTitle().isBlank())
                .map(Chunk::documentTitle)
                .distinct()
                .toList();
    }

    public static double topScore(List<Chunk> chunks) {
        return chunks.stream().mapToDouble(Chunk::score).max().orElse(0.0);
    }
}
