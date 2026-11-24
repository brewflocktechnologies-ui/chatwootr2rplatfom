package com.acme.support.knowledge;

import java.util.List;

/**
 * Retrieval + generation result. Chunk text is carried only for the in-process grounding check and is never logged.
 */
public record RagResult(String answer, List<Chunk> chunks) {

    public record Chunk(double score, String documentTitle, String documentId, String text) {

        public Chunk(double score, String documentTitle, String documentId) {
            this(score, documentTitle, documentId, "");
        }
    }
}
