package com.example.permission.dto;

import java.util.List;
import java.util.Map;

/**
 * Shadow-compare response wrapper — returns BOTH upstream responses plus a
 * pre-computed diff, so operators can inspect verbatim payloads AND the
 * drift-summary in a single round-trip.
 *
 * `permissionResponse` is the same type the real `/api/audit/events` list
 * returns (AuditEventPageResponse). `userServiceResponse` is a raw Map
 * (user-service returns a Map<String,Object>, not a typed DTO — deliberate,
 * to capture verbatim field drift).
 */
public record AuditCompareResponse(
        int page,
        int pageSize,
        AuditEventPageResponse permissionResponse,
        Map<String, Object> userServiceResponse,
        List<String> userServiceErrors,
        AuditCompareDiff diff
) {
}
