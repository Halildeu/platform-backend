package com.example.report.contexthealth.dto;

public record ContextHealthStatusDto(
        boolean enabled,
        String dataDir,
        String indexDir,
        String lastRefresh,
        int fileCount,
        String overallStatus
) {}
