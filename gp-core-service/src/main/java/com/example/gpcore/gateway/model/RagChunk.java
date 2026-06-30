package com.example.gpcore.gateway.model;

import com.example.gpcore.domain.NodeRef;

import java.util.Objects;

/**
 * A resolved RAG chunk whose text is permitted into AI context. Produced ONLY
 * after a {@code RAG_READ} allow against the chunk's AUTHORITATIVE object policy
 * (never the vector-index metadata) — an unauthorized chunk's text is never
 * resolved (ADR-0035 §4; Codex 019f1913 #5).
 */
public record RagChunk(String chunkId, NodeRef objectRef, String text) {

    public RagChunk {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(objectRef, "objectRef");
        Objects.requireNonNull(text, "text");
    }
}
