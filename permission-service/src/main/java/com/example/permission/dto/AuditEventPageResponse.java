package com.example.permission.dto;

import java.util.List;

public record AuditEventPageResponse(
        List<AuditEventResponse> events,
        int page,
        long total
) {
}

