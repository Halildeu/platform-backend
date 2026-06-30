package com.example.gpcore.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Authoritative policy metadata attached to a node (ADR-0034 §6 classification +
 * policy tags; ADR-0035 §3 deny-overrides input). This is NOT node content — it
 * is the minimal attribute set the deny-overrides ABAC layer needs.
 *
 * <p>A {@code present} {@link NodePolicy} always has a non-null classification.
 * Absence of policy (port returns empty) is treated as DENY by the decision
 * service (Codex 019f1913 #7: {@code policyOf} missing/exception/null → deny).
 *
 * <p>Policy tags use the convention {@code "deny:<action>"} (e.g.
 * {@code deny:export}, {@code deny:rag_read}, {@code deny:view}) or
 * {@code "deny:all"} to express an explicit ABAC deny for an action, layered on
 * top of (and overriding) any OpenFGA allow.
 *
 * @param classification data classification (never null)
 * @param legalHold      true when a legal hold is active (blocks export/download/disposition)
 * @param policyTags     ABAC policy tags (e.g. {@code deny:export})
 */
public record NodePolicy(Classification classification, boolean legalHold, Set<String> policyTags) {

    public NodePolicy {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(policyTags, "policyTags");
        policyTags = Set.copyOf(policyTags);
    }

    public static NodePolicy of(Classification classification) {
        return new NodePolicy(classification, false, Set.of());
    }

    public boolean hasTag(String tag) {
        return policyTags.contains(tag);
    }
}
