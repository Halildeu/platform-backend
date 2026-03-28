package com.example.report.registry;

import java.util.Map;

public record ChartDefinition(
        String id,
        String title,
        String chartType,
        String size,
        String source,
        String sourceSchema,
        AggregateSpec aggregate,
        Map<String, Object> chartConfig,
        DrillToSpec drillTo
) {
    public ChartDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Chart id must not be blank");
        }
        if (chartType == null || chartType.isBlank()) {
            chartType = "bar";
        }
        if (size == null || size.isBlank()) {
            size = "md";
        }
        if (sourceSchema == null || sourceSchema.isBlank()) {
            sourceSchema = "dbo";
        }
    }
}
