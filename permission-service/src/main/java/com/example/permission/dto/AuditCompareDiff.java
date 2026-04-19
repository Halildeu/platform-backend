package com.example.permission.dto;

import java.util.List;

/**
 * Diff statistics comparing permission-service vs user-service audit event
 * responses. Used by the shadow-compare endpoint (QLTY-PROACTIVE-06 Faz 2)
 * to collect parity evidence before flipping AUDIT_BACKEND_URI.
 *
 * Field definitions:
 *   totalDelta         permission.total - userService.total
 *   permissionOnlyIds  event ids present in permission response but not user
 *   userServiceOnlyIds event ids present in user response but not permission
 *   commonIds          event ids present in both (basis for fieldDiffs)
 *   fieldDiffs         per-field mismatches found on commonIds (up to 50 entries)
 */
public record AuditCompareDiff(
        long totalDelta,
        long permissionTotal,
        long userServiceTotal,
        List<String> permissionOnlyIds,
        List<String> userServiceOnlyIds,
        List<String> commonIds,
        List<FieldDiff> fieldDiffs,
        String verdict
) {

    public record FieldDiff(
            String eventId,
            String field,
            Object permissionValue,
            Object userServiceValue
    ) {
    }
}
