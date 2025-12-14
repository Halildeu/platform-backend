package com.example.permission.dto;

import java.time.Instant;
import java.util.Map;

public record AuditEventResponse(
        String id,
        Instant timestamp,
        String userEmail,
        String service,
        String level,
        String action,
        String details,
        String correlationId,
        Map<String, Object> metadata,
        Map<String, Object> before,
        Map<String, Object> after
) {
}
