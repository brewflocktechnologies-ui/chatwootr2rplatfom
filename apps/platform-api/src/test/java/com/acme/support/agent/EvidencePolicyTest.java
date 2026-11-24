package com.acme.support.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.support.knowledge.RagResult.Chunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidencePolicyTest {

    private static final double THRESHOLD = 0.50;

    @Test
    void measuredInDomainScoreIsEvidence() {
        List<Chunk> chunks = List.of(new Chunk(0.649, "Acme Demo Support Policy (FICTIONAL)", "d1"),
                new Chunk(0.558, "Acme Demo Support Policy (FICTIONAL)", "d1"));
        assertThat(EvidencePolicy.hasEvidence(chunks, THRESHOLD)).isTrue();
        assertThat(EvidencePolicy.sourceTitles(chunks, THRESHOLD)).containsExactly("Acme Demo Support Policy (FICTIONAL)");
    }

    @Test
    void measuredOutOfDomainScoresAreNotEvidence() {
        // Measured on the pinned stack: "capital of France" 0.265, "ship to Mars" 0.437.
        assertThat(EvidencePolicy.hasEvidence(List.of(new Chunk(0.265, "Policy", "d1")), THRESHOLD)).isFalse();
        assertThat(EvidencePolicy.hasEvidence(List.of(new Chunk(0.437, "Policy", "d1")), THRESHOLD)).isFalse();
    }

    @Test
    void emptyOrUntitledResultsAreNotEvidence() {
        assertThat(EvidencePolicy.hasEvidence(List.of(), THRESHOLD)).isFalse();
        assertThat(EvidencePolicy.hasEvidence(List.of(new Chunk(0.9, " ", "d1")), THRESHOLD)).isFalse();
    }

    @Test
    void sourcesBelowThresholdAreNotCited() {
        List<Chunk> chunks = List.of(new Chunk(0.8, "Shipping", "d1"), new Chunk(0.3, "Returns", "d2"));
        assertThat(EvidencePolicy.sourceTitles(chunks, THRESHOLD)).containsExactly("Shipping");
        assertThat(EvidencePolicy.topScore(chunks)).isEqualTo(0.8);
    }
}
