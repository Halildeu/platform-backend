package com.example.schema.model;

public record Relationship(
    String fromTable,
    String fromColumn,
    String toTable,
    String toColumn,
    double confidence,
    String source,
    boolean multiSource
) {
    public Relationship(String fromTable, String fromColumn, String toTable, String toColumn,
                        double confidence, String source) {
        this(fromTable, fromColumn, toTable, toColumn, confidence, source, false);
    }
}
