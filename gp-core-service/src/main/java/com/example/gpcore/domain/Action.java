package com.example.gpcore.domain;

/**
 * The access action being authorized. The action is chosen by the
 * {@code ReadGateway} method semantics — it is NEVER passed by the caller — so a
 * caller cannot downgrade {@code DOWNLOAD} to {@code VIEW} to slip past a
 * legal-hold/classification deny (Codex 019f1913 #8).
 *
 * <p>Each action maps to a required OpenFGA relation via {@code ActionRelationPolicy}
 * and may attract a NARROWER deny-overrides ABAC rule than {@link #VIEW}.
 */
public enum Action {
    /** In-app read of a node / metadata. */
    VIEW,
    /** Surfacing content into AI/RAG context — narrower ABAC than VIEW. */
    RAG_READ,
    /** Exporting (audit bundle / report) — requires a stronger relation than VIEW. */
    EXPORT,
    /** Downloading evidence content/blob — requires a stronger relation than VIEW. */
    DOWNLOAD
}
