package com.example.report.registry;

public record TrendSpec(
        String select,
        String where,
        String direction
) {
    public TrendSpec {
        if (direction == null || direction.isBlank()) {
            direction = "higher_is_better";
        }
    }
}
