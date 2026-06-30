package com.example.gpcore.port;

import com.example.gpcore.domain.NodePolicy;
import com.example.gpcore.domain.NodeRef;

import java.util.Optional;

/**
 * Authoritative policy-metadata lookup for the deny-overrides ABAC layer. This
 * is policy ONLY (classification / legal-hold / policy-tags) — it never returns
 * node content/text/edge payload, so the authz layer may depend on it without
 * opening a read bypass (Codex 019f1913 #5/#7: authz produces decisions, never
 * resolves content).
 *
 * <p>Returning {@link Optional#empty()} (or throwing) means "no authoritative
 * policy" and is treated as DENY by the decision service.
 */
public interface NodePolicyPort {

    /** Authoritative policy for the node, or empty when unknown (→ deny). */
    Optional<NodePolicy> policyOf(NodeRef ref);
}
