package com.example.gpcore.testsupport;

import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.gateway.model.EvidenceContent;
import com.example.gpcore.gateway.model.EvidenceMetadataView;
import com.example.gpcore.port.content.EvidenceReadPort;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory {@link EvidenceReadPort} that counts content resolutions — so tests
 * can assert the content port is NEVER called on a deny (Codex 019f1913 #8).
 */
public class FakeEvidenceReadPort implements EvidenceReadPort {

    private final Map<NodeRef, EvidenceMetadataView> metadata = new HashMap<>();
    private final Map<NodeRef, EvidenceContent> content = new HashMap<>();
    private final AtomicInteger contentCalls = new AtomicInteger();
    private final AtomicInteger metadataCalls = new AtomicInteger();

    public FakeEvidenceReadPort metadata(NodeRef ref, EvidenceMetadataView m) {
        metadata.put(ref, m);
        return this;
    }

    public FakeEvidenceReadPort content(NodeRef ref, EvidenceContent c) {
        content.put(ref, c);
        return this;
    }

    public int contentCalls() {
        return contentCalls.get();
    }

    public int metadataCalls() {
        return metadataCalls.get();
    }

    @Override
    public Optional<EvidenceMetadataView> findMetadata(NodeRef ref) {
        metadataCalls.incrementAndGet();
        return Optional.ofNullable(metadata.get(ref));
    }

    @Override
    public Optional<EvidenceContent> resolveContent(NodeRef ref) {
        contentCalls.incrementAndGet();
        return Optional.ofNullable(content.get(ref));
    }
}
