package com.example.report.registry;

public record ColumnDefinition(
        String field,
        String headerName,
        String type,
        Integer width,
        boolean sensitive
) {
    public ColumnDefinition {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("Column field must not be blank");
        }
        if (type == null || type.isBlank()) {
            type = "text";
        }
        if (width == null || width <= 0) {
            width = 150;
        }
    }
}
