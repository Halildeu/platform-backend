package com.example.report.registry;

import java.util.List;

public record ReportDefinition(
        String key,
        String version,
        String title,
        String description,
        String category,
        String source,
        String sourceSchema,
        String schemaMode,
        String yearColumn,
        List<ColumnDefinition> columns,
        String defaultSort,
        String defaultSortDirection,
        AccessConfig access
) {
    public ReportDefinition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Report key must not be blank");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Report source (view/table name) must not be blank");
        }
        if (sourceSchema == null || sourceSchema.isBlank()) {
            sourceSchema = "dbo";
        }
        if (schemaMode == null || schemaMode.isBlank()) {
            schemaMode = "static";
        }
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Report must have at least one column");
        }
        if (defaultSortDirection == null || defaultSortDirection.isBlank()) {
            defaultSortDirection = "ASC";
        }
    }

    public boolean isYearlySchema() {
        return "yearly".equals(schemaMode);
    }
}
