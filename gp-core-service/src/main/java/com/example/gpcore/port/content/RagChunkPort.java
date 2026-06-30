package com.example.gpcore.port.content;

import com.example.gpcore.domain.NodeRef;

import java.util.Optional;

/**
 * CONTENT port for RAG chunk resolution (ADR-0035 §4). The vector index stores
 * only {@code object_id, chunk_id, scope, classification, policy_tags} (NO text);
 * a candidate id is NOT a capability. The gateway rehydrates the AUTHORITATIVE
 * object ref from the chunk id and decides {@code RAG_READ} against the
 * authoritative node policy BEFORE any text is resolved (Codex 019f1913 #5).
 *
 * <p>Content port: injectable ONLY by the Read Gateway implementation.
 */
public interface RagChunkPort {

    /**
     * Authoritative object ref backing a chunk id. Empty when the chunk id maps
     * to nothing trustworthy (e.g. forged/stale id) → the gateway denies and
     * never resolves text.
     */
    Optional<NodeRef> authoritativeRef(String chunkId);

    /** The chunk text — resolved ONLY after a RAG_READ allow; never invoked on a deny. */
    Optional<String> resolveChunkText(String chunkId);
}
