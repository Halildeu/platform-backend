package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** #527 slice-1 — an append-only event ledger row (wire-format enums). */
public record RolloutFailureEventResponse(
        UUID id,
        String eventType,
        String fromState,
        String toState,
        String failureClass,
        String sourceSignal,
        Map<String, Object> redactedEvidence,
        String actorType,
        String actorSubjectHash,
        String classificationConfidence,
        Instant createdAt) {
}
