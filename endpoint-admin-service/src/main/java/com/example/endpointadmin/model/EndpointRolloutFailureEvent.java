package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * #527 — append-only audit ledger row. One row per detection / transition; the
 * V60 {@code BEFORE UPDATE OR DELETE} trigger makes it immutable once written.
 * No {@code @Version} (never updated). The §4 transition matrix is enforced
 * fail-closed at the service layer; the V60 CHECKs are the durable backstop
 * (incl. detected -> new only).
 */
@Entity
@Table(name = "endpoint_rollout_failure_event")
public class EndpointRolloutFailureEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "failure_id", nullable = false)
    private UUID failureId;

    @Convert(converter = RolloutFailureEventType.Conv.class)
    @Column(name = "event_type", nullable = false)
    private RolloutFailureEventType eventType;

    @Convert(converter = RolloutFailureState.Conv.class)
    @Column(name = "from_state")
    private RolloutFailureState fromState;

    @Convert(converter = RolloutFailureState.Conv.class)
    @Column(name = "to_state", nullable = false)
    private RolloutFailureState toState;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_class", nullable = false)
    private RolloutFailureClass failureClass;

    @Column(name = "source_signal")
    private String sourceSignal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_evidence_json", nullable = false)
    private Map<String, Object> redactedEvidence = new HashMap<>();

    @Convert(converter = RolloutFailureActorType.Conv.class)
    @Column(name = "actor_type", nullable = false)
    private RolloutFailureActorType actorType;

    @Column(name = "actor_subject_hash")
    private String actorSubjectHash;

    @Convert(converter = RolloutClassificationConfidence.Conv.class)
    @Column(name = "classification_confidence")
    private RolloutClassificationConfidence classificationConfidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public EndpointRolloutFailureEvent() {
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (orgId == null) {
            orgId = tenantId;
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getFailureId() { return failureId; }
    public void setFailureId(UUID failureId) { this.failureId = failureId; }
    public RolloutFailureEventType getEventType() { return eventType; }
    public void setEventType(RolloutFailureEventType v) { this.eventType = v; }
    public RolloutFailureState getFromState() { return fromState; }
    public void setFromState(RolloutFailureState v) { this.fromState = v; }
    public RolloutFailureState getToState() { return toState; }
    public void setToState(RolloutFailureState v) { this.toState = v; }
    public RolloutFailureClass getFailureClass() { return failureClass; }
    public void setFailureClass(RolloutFailureClass v) { this.failureClass = v; }
    public String getSourceSignal() { return sourceSignal; }
    public void setSourceSignal(String v) { this.sourceSignal = v; }
    public Map<String, Object> getRedactedEvidence() { return redactedEvidence; }
    public void setRedactedEvidence(Map<String, Object> v) { this.redactedEvidence = v; }
    public RolloutFailureActorType getActorType() { return actorType; }
    public void setActorType(RolloutFailureActorType v) { this.actorType = v; }
    public String getActorSubjectHash() { return actorSubjectHash; }
    public void setActorSubjectHash(String v) { this.actorSubjectHash = v; }
    public RolloutClassificationConfidence getClassificationConfidence() { return classificationConfidence; }
    public void setClassificationConfidence(RolloutClassificationConfidence v) { this.classificationConfidence = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
