package com.example.permission.dto.v1;

import java.time.Instant;

public record AuditExportJobResponseDto(
        String id,
        String status,
        String format,
        String filename,
        String contentType,
        Integer eventCount,
        String requestedBy,
        Instant createdAt,
        Instant completedAt,
        String errorMessage,
        String downloadPath
) {
}
