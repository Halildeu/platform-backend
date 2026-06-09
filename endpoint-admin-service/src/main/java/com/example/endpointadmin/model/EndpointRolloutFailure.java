package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * #527 failed-device rollout queue — the mutable queue aggregate. One ACTIVE row
 * per (org_id, rollout_id, wave_id, device_id) is enforced by the V60 partial
 * unique index; {@code @Version} provides optimistic locking. The per-class
 * redacted evidence is a JSONB object whose shape is validated against the
 * merged contract JSON schema by {@code FailedDeviceQueueSchemaValidator} BEFORE
 * persistence (slice-1 has no production write path; the test fixture validates).
 */
@Entity
@Table(name = "endpoint_rollout_failure")
public class EndpointRolloutFailure {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "rollout_id", nullable = false)
    private String rolloutId;

    @Column(name = "wave_id", nullable = false)
    private String waveId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_class", nullable = false)
    private RolloutFailureClass currentClass;

    @Convert(converter = RolloutFailureState.Conv.class)
    @Column(name = "current_state", nullable = false)
    private RolloutFailureState currentState;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    @Column(name = "first_detected_at", nullable = false)
    private Instant firstDetectedAt;

    @Column(name = "last_observed_at", nullable = false)
    private Instant lastObservedAt;

    @Column(name = "last_transition_at", nullable = false)
    private Instant lastTransitionAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_redacted_json", nullable = false)
    private Map<String, Object> evidenceRedacted = new HashMap<>();

    @Column(name = "owner_role", nullable = false)
    private String ownerRole;

    @Column(name = "stop_line_contribution")
    private Boolean stopLineContribution;

    @Column(name = "escalation_issue_url")
    private String escalationIssueUrl;

    @Column(name = "waiver_reason")
    private String waiverReason;

    @Column(name = "waived_by")
    private String waivedBy;

    @Column(name = "waived_until")
    private Instant waivedUntil;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_summary")
    private String resolutionSummary;

    @Convert(converter = RolloutClassificationConfidence.Conv.class)
    @Column(name = "classification_confidence", nullable = false)
    private RolloutClassificationConfidence classificationConfidence;

    @Column(name = "classifier_version")
    private String classifierVersion;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public EndpointRolloutFailure() {
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        canonicalizeOrg();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        canonicalizeOrg();
    }

    /** org_id is canonical and equals tenant_id (Faz 21.1 + the V60 CHECK + trigger). */
    private void canonicalizeOrg() {
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
    public String getRolloutId() { return rolloutId; }
    public void setRolloutId(String rolloutId) { this.rolloutId = rolloutId; }
    public String getWaveId() { return waveId; }
    public void setWaveId(String waveId) { this.waveId = waveId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public RolloutFailureClass getCurrentClass() { return currentClass; }
    public void setCurrentClass(RolloutFailureClass currentClass) { this.currentClass = currentClass; }
    public RolloutFailureState getCurrentState() { return currentState; }
    public void setCurrentState(RolloutFailureState currentState) { this.currentState = currentState; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public Instant getFirstDetectedAt() { return firstDetectedAt; }
    public void setFirstDetectedAt(Instant v) { this.firstDetectedAt = v; }
    public Instant getLastObservedAt() { return lastObservedAt; }
    public void setLastObservedAt(Instant v) { this.lastObservedAt = v; }
    public Instant getLastTransitionAt() { return lastTransitionAt; }
    public void setLastTransitionAt(Instant v) { this.lastTransitionAt = v; }
    public Map<String, Object> getEvidenceRedacted() { return evidenceRedacted; }
    public void setEvidenceRedacted(Map<String, Object> v) { this.evidenceRedacted = v; }
    public String getOwnerRole() { return ownerRole; }
    public void setOwnerRole(String v) { this.ownerRole = v; }
    public Boolean getStopLineContribution() { return stopLineContribution; }
    public void setStopLineContribution(Boolean v) { this.stopLineContribution = v; }
    public String getEscalationIssueUrl() { return escalationIssueUrl; }
    public void setEscalationIssueUrl(String v) { this.escalationIssueUrl = v; }
    public String getWaiverReason() { return waiverReason; }
    public void setWaiverReason(String v) { this.waiverReason = v; }
    public String getWaivedBy() { return waivedBy; }
    public void setWaivedBy(String v) { this.waivedBy = v; }
    public Instant getWaivedUntil() { return waivedUntil; }
    public void setWaivedUntil(Instant v) { this.waivedUntil = v; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { this.resolvedAt = v; }
    public String getResolutionSummary() { return resolutionSummary; }
    public void setResolutionSummary(String v) { this.resolutionSummary = v; }
    public RolloutClassificationConfidence getClassificationConfidence() { return classificationConfidence; }
    public void setClassificationConfidence(RolloutClassificationConfidence v) { this.classificationConfidence = v; }
    public String getClassifierVersion() { return classifierVersion; }
    public void setClassifierVersion(String v) { this.classifierVersion = v; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
