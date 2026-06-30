package com.example.gpcore.gateway.model;

import java.util.List;

/**
 * Search output: ONLY visible results. Deliberately no {@code total},
 * {@code hasMore}, cursor, rank, or score field — those are classic
 * count/side-channel leaks for hidden matches (Codex 019f1913 #9). Adding a
 * hidden candidate must not change this result.
 */
public record SearchResult(List<NodeView> results) {

    public SearchResult {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public static SearchResult empty() {
        return new SearchResult(List.of());
    }
}
