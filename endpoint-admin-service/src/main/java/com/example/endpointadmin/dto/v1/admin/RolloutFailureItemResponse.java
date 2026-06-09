package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** #527 slice-1 — a queue aggregate item (wire-format enums; org_id internal, not exposed). */
public record RolloutFailureItemResponse(
        UUID id,
        String rolloutId,
        String waveId,
        UUID deviceId,
        String failureClass,
        String state,
        int retryCount,
        int maxRetries,
        Instant firstDetectedAt,
        Instant lastObservedAt,
        Instant lastTransitionAt,
        Map<String, Object> evidenceRedacted,
        String ownerRole,
        Boolean stopLineContribution,
        String escalationIssueUrl,
        String waiverReason,
        String waivedBy,
        Instant waivedUntil,
        Instant resolvedAt,
        String resolutionSummary,
        String classificationConfidence,
        String classifierVersion,
        long version) {
}
