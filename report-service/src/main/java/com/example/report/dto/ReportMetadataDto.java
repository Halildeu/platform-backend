package com.example.report.dto;

import com.example.report.registry.ColumnDefinition;
import java.util.List;

public record ReportMetadataDto(
        String key,
        String title,
        String description,
        String category,
        List<ColumnDefinition> columns,
        String defaultSort,
        String defaultSortDirection
) {}
