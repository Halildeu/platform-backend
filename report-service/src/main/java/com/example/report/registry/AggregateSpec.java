package com.example.report.registry;

public record AggregateSpec(
        String select,
        String where,
        String groupBy,
        String orderBy,
        Integer limit,
        String join
) {
    public AggregateSpec {
        if (select == null || select.isBlank()) {
            throw new IllegalArgumentException("Aggregate select expression must not be blank");
        }
    }
}
