package com.example.gpcore.port;

import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.domain.Principal;

import java.util.List;

/**
 * The OpenFGA "who is related" plane (ADR-0035: OpenFGA = data-plane policy
 * enforcement point). Returns whether a principal holds a given relation on an
 * object. Implementations MUST be fail-closed: any uncertainty (error, circuit
 * open, disabled store) returns {@code false} (Codex 019f1913 #2 — gp-core must
 * NOT inherit the dev allow-all default).
 *
 * <p>This is a decision-INPUT port (consumed by
 * {@link com.example.gpcore.authz.AuthorizationDecisionService}); it carries no
 * node content, so the authz layer may depend on it.
 */
public interface RelationshipChecker {

    /** True iff {@code principal} holds {@code relation} on {@code ref}. Fail-closed. */
    boolean canRelate(Principal principal, String relation, NodeRef ref);

    /**
     * Batch variant for bounded bulk authorization. The returned list is
     * positionally aligned with {@code requests}; any item that cannot be
     * evaluated MUST be {@code false} (fail-closed per item — Codex 019f1913 #7).
     */
    List<Boolean> canRelateBatch(Principal principal, List<RelationRequest> requests);

    record RelationRequest(String relation, NodeRef ref) {}
}
