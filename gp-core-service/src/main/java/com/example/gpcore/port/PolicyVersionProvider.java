package com.example.gpcore.port;

/**
 * Supplies the version stamps that make the decision cache key version-aware
 * (ADR-0035 §6). A change in either value invalidates cached positives, so a
 * decision can never be served from a stale layer (Codex 019f1913 #2/#7).
 *
 * <p>If either value cannot be produced (exception), the decision service treats
 * the decision as DENY — a cache key cannot be safely formed without freshness
 * guarantees.
 */
public interface PolicyVersionProvider {

    /** Version of the ABAC policy set (classification rules, deny-tag semantics). */
    String policyVersion();

    /** Revision/snapshot token of the underlying OpenFGA tuples (relationship plane). */
    String tupleRevision();
}
