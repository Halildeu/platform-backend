package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.ClassificationConfidence;
import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureState;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection of an append-only rollout-failure ledger event (Faz 22.5 #527
 * slice-1a). {@code actorSubjectHash} is the server-side stable hash, never a raw
 * subject.
 */
public record RolloutFailureEventResponse(
        UUID id,
        UUID failureId,
        String eventType,
        RolloutFailureState fromState,
        RolloutFailureState toState,
        RolloutFailureClass failureClass,
        String sourceSignal,
        JsonNode redactedEvidence,
        String actorType,
        String actorSubjectHash,
        ClassificationConfidence classificationConfidence,
        Instant createdAt) {

    public static RolloutFailureEventResponse from(EndpointRolloutFailureEvent e) {
        return new RolloutFailureEventResponse(
                e.getId(), e.getFailureId(), e.getEventType(), e.getFromState(), e.getToState(),
                e.getFailureClass(), e.getSourceSignal(), e.getRedactedEvidenceJson(),
                e.getActorType(), e.getActorSubjectHash(), e.getClassificationConfidence(),
                e.getCreatedAt());
    }
}
