package com.example.report.dto;

import com.example.report.registry.LayoutConfig;

import java.util.List;

public record DashboardMetadataDto(
        String key,
        String title,
        String description,
        String category,
        String icon,
        List<String> timeRanges,
        String defaultTimeRange,
        LayoutConfig layout,
        List<KpiMetaDto> kpis,
        List<ChartMetaDto> charts
) {
    public record KpiMetaDto(String id, String title, String format) {}
    public record ChartMetaDto(String id, String title, String chartType, String size) {}
}
