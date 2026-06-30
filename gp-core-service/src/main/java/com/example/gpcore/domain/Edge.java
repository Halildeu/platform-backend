package com.example.gpcore.domain;

import java.util.Objects;

/**
 * A typed graph edge (ontology v2 {@code (type, from, to, scope)}). Sensitive
 * relationships (coverage_mapping, evidence_binding) are modelled as reified
 * <em>nodes</em>, not edges, so they go through node-level decisions; bare edges
 * here are structural and carry an {@code edge_scope} for scoping.
 *
 * <p>Edge visibility (ADR-0035 §2): an edge is visible iff
 * {@code can_view(source) AND can_view(target) AND can_view(edge_scope)}. A
 * {@code null} scope is only legitimate for an explicit global-edge allowlist;
 * otherwise a null scope on a scoped edge is fail-closed (Codex 019f1913 #6).
 *
 * @param edgeType relation type (e.g. {@code contains}, {@code implements})
 * @param source   from-node ref
 * @param target   to-node ref
 * @param scope    edge_scope node ref; may be {@code null} only for allowlisted
 *                 global edge types
 */
public record Edge(String edgeType, NodeRef source, NodeRef target, NodeRef scope) {

    public Edge {
        Objects.requireNonNull(edgeType, "edgeType");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (edgeType.isBlank()) {
            throw new IllegalArgumentException("edgeType must not be blank");
        }
    }

    public boolean hasScope() {
        return scope != null;
    }
}
