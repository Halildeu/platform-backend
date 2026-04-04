package com.example.report.contexthealth.dto;

import java.util.List;

public record ContextHealthGridMetaDto(
        String gridId,
        String title,
        List<ColumnDef> columns
) {
    public record ColumnDef(
            String field,
            String headerName,
            String type,
            int width
    ) {}
}
