package com.example.gpcore.testsupport;

import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.port.content.RagChunkPort;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link RagChunkPort} that counts text resolutions — so tests can
 * assert chunk text is NEVER resolved for an unauthorized / forged chunk id
 * (ADR-0035 §4; Codex 019f1913 #5).
 */
public class FakeRagChunkPort implements RagChunkPort {

    private final Map<String, NodeRef> authoritativeRefs = new HashMap<>();
    private final Map<String, String> texts = new HashMap<>();
    private final AtomicInteger textCalls = new AtomicInteger();

    /** Register a chunk that maps to an authoritative object ref and has text. */
    public FakeRagChunkPort chunk(String chunkId, NodeRef authoritativeRef, String text) {
        authoritativeRefs.put(chunkId, authoritativeRef);
        texts.put(chunkId, text);
        return this;
    }

    /** Register a chunk id with NO authoritative backing (forged/stale). */
    public FakeRagChunkPort orphanChunk(String chunkId, String text) {
        texts.put(chunkId, text);
        return this;
    }

    public int textCalls() {
        return textCalls.get();
    }

    @Override
    public Optional<NodeRef> authoritativeRef(String chunkId) {
        return Optional.ofNullable(authoritativeRefs.get(chunkId));
    }

    @Override
    public Optional<String> resolveChunkText(String chunkId) {
        textCalls.incrementAndGet();
        return Optional.ofNullable(texts.get(chunkId));
    }
}
