package com.example.gpcore.gateway.model;

import com.example.gpcore.domain.NodeRef;

import java.util.Objects;

/**
 * A bounded, permission-filtered traversal request (ontology v2 §4). Depth and
 * budget are MANDATORY bounds — unbounded graph exploration is forbidden. The
 * budget caps VISIBLE results returned; hidden nodes are never expanded and never
 * consume the visible budget (Codex 019f1913 #9).
 *
 * @param entry  entry node
 * @param depth  max hop depth (&gt;= 1)
 * @param budget max number of VISIBLE nodes to return (&gt;= 1)
 * @param query  edge/node-type filter
 */
public record TraverseRequest(NodeRef entry, int depth, int budget, EdgeQuery query) {

    public TraverseRequest {
        Objects.requireNonNull(entry, "entry");
        if (depth < 1) {
            throw new IllegalArgumentException("depth must be >= 1");
        }
        if (budget < 1) {
            throw new IllegalArgumentException("budget must be >= 1");
        }
        query = query == null ? EdgeQuery.all() : query;
    }
}
