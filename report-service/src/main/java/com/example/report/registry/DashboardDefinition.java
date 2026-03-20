package com.example.report.registry;

import java.util.List;

public record DashboardDefinition(
        String type,
        String key,
        String version,
        String title,
        String description,
        String category,
        String icon,
        AccessConfig access,
        List<String> timeRanges,
        String defaultTimeRange,
        List<KpiDefinition> kpis,
        List<ChartDefinition> charts,
        LayoutConfig layout
) {
    public DashboardDefinition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Dashboard key must not be blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Dashboard title must not be blank");
        }
        if (type == null || type.isBlank()) {
            type = "dashboard";
        }
        if (defaultTimeRange == null || defaultTimeRange.isBlank()) {
            defaultTimeRange = "90d";
        }
        if (timeRanges == null || timeRanges.isEmpty()) {
            timeRanges = List.of("30d", "90d", "1y", "ytd");
        }
        if (kpis == null) {
            kpis = List.of();
        }
        if (charts == null) {
            charts = List.of();
        }
    }
}
