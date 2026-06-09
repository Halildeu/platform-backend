package com.example.endpointadmin.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit ledger row for the rollout failed-device queue (Faz 22.5
 * #527 slice-1a, contract §2/§4). Every state transition writes one row; the DB
 * trigger {@code trg_erfe_append_only} rejects any UPDATE/DELETE. No setters,
 * no {@code @Version}: rows are immutable once written.
 *
 * <p>{@code actorType} is stored as a plain String (DB CHECK domain
 * {@code auto|operator|system}); slice-1a manual create only writes
 * {@code operator}. {@code actorSubjectHash} is a server-side stable hash — never
 * a raw UPN/email/JWT subject (contract §7 redaction).
 */
@Entity
@Table(name = "endpoint_rollout_failure_event")
public class EndpointRolloutFailureEvent {

    public static final String ACTOR_OPERATOR = "operator";
    // Contract initial event (schema $defs.event.event_type): a fresh observation
    // is `detected` (NOT a generic "created").
    public static final String EVENT_DETECTED = "detected";
    public static final String SOURCE_MANUAL_OPERATOR = "manual_operator";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "failure_id", nullable = false, updatable = false)
    private UUID failureId;

    @Column(name = "event_type", nullable = false, length = 32, updatable = false)
    private String eventType;

    @Convert(converter = RolloutFailureStateConverter.class)
    @Column(name = "from_state", length = 16, updatable = false)
    private RolloutFailureState fromState;

    @Convert(converter = RolloutFailureStateConverter.class)
    @Column(name = "to_state", nullable = false, length = 16, updatable = false)
    private RolloutFailureState toState;

    @Enumerated(EnumType.STRING)
    @Column(name = "class", nullable = false, length = 32, updatable = false)
    private RolloutFailureClass failureClass;

    @Column(name = "source_signal", nullable = false, length = 64, updatable = false)
    private String sourceSignal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_evidence_json", nullable = false, updatable = false)
    private JsonNode redactedEvidenceJson;

    @Column(name = "actor_type", nullable = false, length = 16, updatable = false)
    private String actorType;

    @Column(name = "actor_subject_hash", length = 64, updatable = false)
    private String actorSubjectHash;

    @Convert(converter = ClassificationConfidenceConverter.class)
    @Column(name = "classification_confidence", nullable = false, length = 8, updatable = false)
    private ClassificationConfidence classificationConfidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EndpointRolloutFailureEvent() {
        // JPA
    }

    /** Initial `detected` event for a manual operator seed (from_state = null). */
    public static EndpointRolloutFailureEvent detectedManual(
            UUID id, EndpointRolloutFailure failure, JsonNode redactedEvidenceJson,
            String actorSubjectHash, Instant createdAt) {
        EndpointRolloutFailureEvent e = new EndpointRolloutFailureEvent();
        e.id = id;
        e.tenantId = failure.getTenantId();
        e.orgId = failure.getOrgId();
        e.failureId = failure.getId();
        e.eventType = EVENT_DETECTED;
        e.fromState = null;
        e.toState = RolloutFailureState.NEW;
        e.failureClass = failure.getCurrentClass();
        e.sourceSignal = SOURCE_MANUAL_OPERATOR;
        e.redactedEvidenceJson = redactedEvidenceJson;
        e.actorType = ACTOR_OPERATOR;
        e.actorSubjectHash = actorSubjectHash;
        e.classificationConfidence = failure.getClassificationConfidence();
        e.createdAt = createdAt;
        return e;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getFailureId() {
        return failureId;
    }

    public String getEventType() {
        return eventType;
    }

    public RolloutFailureState getFromState() {
        return fromState;
    }

    public RolloutFailureState getToState() {
        return toState;
    }

    public RolloutFailureClass getFailureClass() {
        return failureClass;
    }

    public String getSourceSignal() {
        return sourceSignal;
    }

    public JsonNode getRedactedEvidenceJson() {
        return redactedEvidenceJson;
    }

    public String getActorType() {
        return actorType;
    }

    public String getActorSubjectHash() {
        return actorSubjectHash;
    }

    public ClassificationConfidence getClassificationConfidence() {
        return classificationConfidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
