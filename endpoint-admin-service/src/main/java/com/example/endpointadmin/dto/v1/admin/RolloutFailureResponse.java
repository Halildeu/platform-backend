package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.ClassificationConfidence;
import com.example.endpointadmin.model.EndpointRolloutFailure;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureState;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection of a rollout failed-device queue aggregate (Faz 22.5 #527
 * slice-1a). Carries only the redacted evidence object (already allowlisted on
 * write) — never raw last_error/tokens. tenant_id is intentionally NOT exposed
 * (org scope is implicit in the authenticated tenant).
 */
public record RolloutFailureResponse(
        UUID id,
        String rolloutId,
        String waveId,
        UUID deviceId,
        RolloutFailureClass currentClass,
        RolloutFailureState currentState,
        int retryCount,
        int maxRetries,
        ClassificationConfidence classificationConfidence,
        String classifierVersion,
        String ownerRole,
        JsonNode evidenceRedacted,
        String escalationIssueUrl,
        Instant firstDetectedAt,
        Instant lastObservedAt,
        Instant lastTransitionAt) {

    public static RolloutFailureResponse from(EndpointRolloutFailure f) {
        return new RolloutFailureResponse(
                f.getId(), f.getRolloutId(), f.getWaveId(), f.getDeviceId(),
                f.getCurrentClass(), f.getCurrentState(), f.getRetryCount(), f.getMaxRetries(),
                f.getClassificationConfidence(), f.getClassifierVersion(), f.getOwnerRole(),
                f.getEvidenceRedactedJson(), f.getEscalationIssueUrl(),
                f.getFirstDetectedAt(), f.getLastObservedAt(), f.getLastTransitionAt());
    }
}
