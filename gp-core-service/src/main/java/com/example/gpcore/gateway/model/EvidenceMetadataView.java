package com.example.gpcore.gateway.model;

import com.example.gpcore.domain.NodeRef;

import java.util.Objects;

/**
 * Evidence metadata (records-model fields, ADR-0034 §3) — the VIEW-action result.
 * Carries the immutable id, hash-chain ref, retention class and legal-hold flag,
 * but NOT the WORM blob content (that is {@link EvidenceContent}, a DOWNLOAD action).
 */
public record EvidenceMetadataView(NodeRef ref, String immutableId, String hashChainRef,
                                   String retentionClass, boolean legalHold) {

    public EvidenceMetadataView {
        Objects.requireNonNull(ref, "ref");
    }
}
