package com.example.schema.model;

import java.util.List;

/**
 * One join edge of the relationship graph.
 *
 * <p>{@code fromColumn}/{@code toColumn} are the representative pair — for a
 * single-column relationship the only one, for a composite key the LAST pair
 * (the referenced side's own key). {@code fromColumns}/{@code toColumns} carry
 * every pair in key order, so anything that emits SQL joins on all of them
 * ({@code AND}-ed); a composite join on its last pair alone returns rows from
 * other parents (gitops#3631, Codex 01a08afc P1). The two lists are always the
 * same length and never empty; for a single-column edge they hold the one pair.
 */
public record Relationship(
    String fromTable,
    String fromColumn,
    String toTable,
    String toColumn,
    double confidence,
    String source,
    boolean multiSource,
    List<String> fromColumns,
    List<String> toColumns
) {
    public Relationship {
        if (fromColumns == null || fromColumns.isEmpty()) fromColumns = List.of(fromColumn);
        if (toColumns == null || toColumns.isEmpty()) toColumns = List.of(toColumn);
        if (fromColumns.size() != toColumns.size()) {
            throw new IllegalArgumentException("relationship " + fromTable + " -> " + toTable
                + " has " + fromColumns.size() + " source columns and " + toColumns.size() + " target columns");
        }
        fromColumns = List.copyOf(fromColumns);
        toColumns = List.copyOf(toColumns);
    }

    public Relationship(String fromTable, String fromColumn, String toTable, String toColumn,
                        double confidence, String source) {
        this(fromTable, fromColumn, toTable, toColumn, confidence, source, false, null, null);
    }

    public Relationship(String fromTable, String fromColumn, String toTable, String toColumn,
                        double confidence, String source, boolean multiSource) {
        this(fromTable, fromColumn, toTable, toColumn, confidence, source, multiSource, null, null);
    }

    /** A composite edge: {@link #fromColumns()} has more than one pair. */
    public boolean isComposite() {
        return fromColumns.size() > 1;
    }

    /** True when {@code column} of {@code table} takes part in this edge on its source side. */
    public boolean joinsFrom(String table, String column) {
        return fromTable.equals(table) && fromColumns.contains(column);
    }

    /** True when {@code column} of {@code table} takes part in this edge on its target side. */
    public boolean joinsTo(String table, String column) {
        return toTable.equals(table) && toColumns.contains(column);
    }
}
