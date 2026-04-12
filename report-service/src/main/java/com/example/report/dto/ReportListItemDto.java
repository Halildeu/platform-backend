package com.example.report.dto;

public record ReportListItemDto(
        String key,
        String title,
        String description,
        String category,
        /** CNS-006 R18: report group for deny-default frontend filtering */
        String reportGroup
) {}
