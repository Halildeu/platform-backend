package com.example.gpcore.gateway.model;

import com.example.gpcore.domain.NodeRef;

import java.util.Objects;

/**
 * A handle to evidence content (WORM object reference) — the DOWNLOAD-action
 * result. Resolved ONLY after a {@code DOWNLOAD} allow; the content port is never
 * invoked on a deny (Codex 019f1913 #8).
 */
public record EvidenceContent(NodeRef ref, String contentRef) {

    public EvidenceContent {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(contentRef, "contentRef");
    }
}
