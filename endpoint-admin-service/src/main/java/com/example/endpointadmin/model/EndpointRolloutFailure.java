package com.example.endpointadmin.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Rollout failed-device queue aggregate — the system-of-record for ONE active
 * failure per device per wave (Faz 22.5 #527 slice-1a, contract §2). The DB
 * partial-unique index {@code uq_endpoint_rollout_failure_active} (active states
 * only) is the authority; this entity does not re-declare it (resolved/waived
 * rows stay as history).
 *
 * <p>{@code org_id == tenant_id} (Faz 21.1 canonical); the org-composite FK
 * {@code (device_id, org_id) -> endpoint_devices(id, org_id)} is enforced in the
 * migration. {@code evidence_redacted_json} is a per-class allowlisted redacted
 * object validated at the service layer (code-defined allowlist registry, pinned
 * to the contract schema by a drift test) before persistence; the DB
 * only backstops {@code jsonb_typeof = 'object'}.
 */
@Entity
@Table(name = "endpoint_rollout_failure")
public class EndpointRolloutFailure {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    // rollout_id / wave_id are opaque operator-assigned identifiers, NOT UUIDs
    // (contract schema $defs.queueItem: type=string, e.g. "rollout-2026-q3-…").
    @Column(name = "rollout_id", nullable = false, updatable = false, length = 128)
    private String rolloutId;

    @Column(name = "wave_id", nullable = false, updatable = false, length = 128)
    private String waveId;

    @Column(name = "device_id", nullable = false, updatable = false)
    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_class", nullable = false, length = 32)
    private RolloutFailureClass currentClass;

    @Convert(converter = RolloutFailureStateConverter.class)
    @Column(name = "current_state", nullable = false, length = 16)
    private RolloutFailureState currentState;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Convert(converter = ClassificationConfidenceConverter.class)
    @Column(name = "classification_confidence", nullable = false, length = 8)
    private ClassificationConfidence classificationConfidence;

    @Column(name = "classifier_version", nullable = false, length = 64)
    private String classifierVersion;

    @Column(name = "owner_role", length = 64)
    private String ownerRole;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_redacted_json", nullable = false)
    private JsonNode evidenceRedactedJson;

    @Column(name = "escalation_issue_url", length = 512)
    private String escalationIssueUrl;

    @Column(name = "waiver_reason", length = 512)
    private String waiverReason;

    @Column(name = "waived_by", length = 255)
    private String waivedBy;

    @Column(name = "waived_until")
    private Instant waivedUntil;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_summary", length = 512)
    private String resolutionSummary;

    @Column(name = "first_detected_at", nullable = false, updatable = false)
    private Instant firstDetectedAt;

    @Column(name = "last_observed_at", nullable = false)
    private Instant lastObservedAt;

    @Column(name = "last_transition_at", nullable = false)
    private Instant lastTransitionAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected EndpointRolloutFailure() {
        // JPA
    }

    // Slice-1a manual-create factory: server-derived initial `new` observation.
    public static EndpointRolloutFailure newManual(
            UUID id, UUID tenantId, String rolloutId, String waveId, UUID deviceId,
            RolloutFailureClass failureClass, int maxRetries,
            ClassificationConfidence confidence, String classifierVersion,
            String ownerRole, JsonNode evidenceRedactedJson, Instant detectedAt) {
        EndpointRolloutFailure f = new EndpointRolloutFailure();
        f.id = id;
        f.tenantId = tenantId;
        f.orgId = tenantId; // org_id == tenant_id (Faz 21.1 canonical)
        f.rolloutId = rolloutId;
        f.waveId = waveId;
        f.deviceId = deviceId;
        f.currentClass = failureClass;
        f.currentState = RolloutFailureState.NEW;
        f.retryCount = 0;
        f.maxRetries = maxRetries;
        f.classificationConfidence = confidence;
        f.classifierVersion = classifierVersion;
        f.ownerRole = ownerRole;
        f.evidenceRedactedJson = evidenceRedactedJson;
        f.firstDetectedAt = detectedAt;
        f.lastObservedAt = detectedAt;
        f.lastTransitionAt = detectedAt;
        return f;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getRolloutId() {
        return rolloutId;
    }

    public String getWaveId() {
        return waveId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public RolloutFailureClass getCurrentClass() {
        return currentClass;
    }

    public RolloutFailureState getCurrentState() {
        return currentState;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public ClassificationConfidence getClassificationConfidence() {
        return classificationConfidence;
    }

    public String getClassifierVersion() {
        return classifierVersion;
    }

    public String getOwnerRole() {
        return ownerRole;
    }

    public JsonNode getEvidenceRedactedJson() {
        return evidenceRedactedJson;
    }

    public String getEscalationIssueUrl() {
        return escalationIssueUrl;
    }

    public String getWaiverReason() {
        return waiverReason;
    }

    public String getWaivedBy() {
        return waivedBy;
    }

    public Instant getWaivedUntil() {
        return waivedUntil;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolutionSummary() {
        return resolutionSummary;
    }

    public Instant getFirstDetectedAt() {
        return firstDetectedAt;
    }

    public Instant getLastObservedAt() {
        return lastObservedAt;
    }

    public Instant getLastTransitionAt() {
        return lastTransitionAt;
    }

    public long getVersion() {
        return version;
    }
}
