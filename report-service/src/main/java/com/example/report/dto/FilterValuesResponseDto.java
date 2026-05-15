package com.example.report.dto;

import java.util.List;

/**
 * PR-0.5c (Codex thread 019e2d54) — response envelope for
 * {@code GET /api/v1/reports/{key}/filter-values}.
 *
 * <p>The AG Grid set filter dropdown consumes {@code values} directly
 * via its {@code filterParams.values} async callback. Values are raw
 * column objects: a {@code null} entry maps to AG Grid's "(Blanks)"
 * row, numbers/dates round-trip as their JSON-native type. The
 * backend never substitutes a sentinel string for null.
 *
 * @param values    Distinct column values, sorted ascending, capped at
 *                  {@code limit}. May contain {@code null}.
 * @param limit     The effective cap applied (request limit clamped to
 *                  {@code report.query.max-filter-values}).
 * @param truncated {@code true} when the column has more distinct
 *                  values than {@code limit} — the frontend surfaces
 *                  a "showing first N, refine your search" hint.
 */
public record FilterValuesResponseDto(
        List<Object> values,
        int limit,
        boolean truncated) {

    public FilterValuesResponseDto {
        // Null-preserving immutable copy: List.copyOf rejects null
        // elements, but a column's distinct set legitimately includes
        // null (AG Grid "(Blanks)"). Wrap an ArrayList instead so the
        // null survives to the JSON response. (Same null-tolerance
        // lesson as PR-0.5a's grandTotalRow — Codex 019e2c61 §High.)
        values = values == null
                ? List.of()
                : java.util.Collections.unmodifiableList(
                        new java.util.ArrayList<>(values));
    }
}

