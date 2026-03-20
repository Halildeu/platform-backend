package com.example.report.registry;

import java.util.List;

public record KpiDefinition(
        String id,
        String title,
        String format,
        String tone,
        List<ToneRule> toneRules,
        String source,
        String sourceSchema,
        AggregateSpec aggregate,
        TrendSpec trend,
        BenchmarkSpec benchmark,
        DrillToSpec drillTo
) {
    public KpiDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("KPI id must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("KPI title must not be blank");
        }
        if (format == null || format.isBlank()) {
            format = "number";
        }
        if (sourceSchema == null || sourceSchema.isBlank()) {
            sourceSchema = "dbo";
        }
    }
}
