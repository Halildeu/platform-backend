package com.example.gpcore.gateway.model;

import java.util.List;

/**
 * A permission-filtered graph slice: ONLY visible nodes and edges. There is no
 * hidden-count, total, or "N more" field — a denied/hidden node must not leak
 * even as a number (ADR-0035 §2 count-leak; Codex 019f1913 #9 side-channels).
 */
public record GraphView(List<NodeView> nodes, List<EdgeView> edges) {

    public GraphView {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public static GraphView empty() {
        return new GraphView(List.of(), List.of());
    }
}
