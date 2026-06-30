package com.example.gpcore.gateway.model;

import java.util.Set;

/**
 * Filter for edge expansion / traversal. Null or empty sets mean "no filter on
 * that dimension". Filtering is applied to CANDIDATE edges before the
 * edge-visibility decision; it never relaxes enforcement.
 */
public record EdgeQuery(Set<String> edgeTypes, Set<String> nodeTypes) {

    public EdgeQuery {
        edgeTypes = edgeTypes == null ? Set.of() : Set.copyOf(edgeTypes);
        nodeTypes = nodeTypes == null ? Set.of() : Set.copyOf(nodeTypes);
    }

    public static EdgeQuery all() {
        return new EdgeQuery(Set.of(), Set.of());
    }

    public boolean matchesEdgeType(String edgeType) {
        return edgeTypes.isEmpty() || edgeTypes.contains(edgeType);
    }

    public boolean matchesNodeType(String nodeType) {
        return nodeTypes.isEmpty() || nodeTypes.contains(nodeType);
    }
}
