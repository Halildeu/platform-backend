package com.example.report.dto;

import com.example.report.registry.BenchmarkSpec;
import com.example.report.registry.DrillToSpec;

public record KpiResultDto(
        String id,
        String title,
        String format,
        Object value,
        String formattedValue,
        TrendDto trend,
        String tone,
        BenchmarkSpec benchmark,
        DrillToSpec drillTo
) {
    public record TrendDto(
            String direction,
            double percentage
    ) {}
}
