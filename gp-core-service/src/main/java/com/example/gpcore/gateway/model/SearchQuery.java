package com.example.gpcore.gateway.model;

import java.util.Set;

/**
 * A search request. {@code limit} caps VISIBLE results; the gateway scans
 * candidates under an internal cap but never reflects hidden matches in the
 * response (no total/hasMore) — Codex 019f1913 #9.
 *
 * @param text      free-text query
 * @param nodeTypes optional node-type filter (empty = all)
 * @param limit     max VISIBLE results to return (&gt;= 1)
 */
public record SearchQuery(String text, Set<String> nodeTypes, int limit) {

    public SearchQuery {
        text = text == null ? "" : text;
        nodeTypes = nodeTypes == null ? Set.of() : Set.copyOf(nodeTypes);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
    }

    public boolean matchesNodeType(String nodeType) {
        return nodeTypes.isEmpty() || nodeTypes.contains(nodeType);
    }
}
