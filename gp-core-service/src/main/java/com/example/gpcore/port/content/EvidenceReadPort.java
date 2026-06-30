package com.example.gpcore.port.content;

import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.gateway.model.EvidenceContent;
import com.example.gpcore.gateway.model.EvidenceMetadataView;

import java.util.Optional;

/**
 * CONTENT port for evidence (ADR-0034 §3 records boundary; WORM blob in a later
 * wave). Metadata vs content are separate so the gateway can gate them with
 * different actions (VIEW vs DOWNLOAD) and never resolve content on a deny.
 *
 * <p>Content port: injectable ONLY by the Read Gateway implementation.
 */
public interface EvidenceReadPort {

    /** Records-model metadata (immutable id, hash-chain, retention, legal-hold) — VIEW action. */
    Optional<EvidenceMetadataView> findMetadata(NodeRef ref);

    /** WORM content handle — DOWNLOAD action; invoked only after a DOWNLOAD allow. */
    Optional<EvidenceContent> resolveContent(NodeRef ref);
}
