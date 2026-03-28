package com.example.report.dto;

import java.util.List;

public record DashboardListItemDto(
        String key,
        String title,
        String description,
        String category,
        String icon,
        List<String> timeRanges,
        String defaultTimeRange,
        int kpiCount,
        int chartCount
) {}
