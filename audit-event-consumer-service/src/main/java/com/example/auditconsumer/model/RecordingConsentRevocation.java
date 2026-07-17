package com.example.auditconsumer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Immutable canonical projection of one accepted recorder-consent withdrawal. */
@Entity
@Immutable
@Table(name = "recording_consent_revocation")
public class RecordingConsentRevocation {

    @Id
    private UUID id;
    @Column(name = "event_key", nullable = false, unique = true, updatable = false, length = 240)
    private String eventKey;
    @Column(name = "source_hash", nullable = false, updatable = false, length = 64)
    private String sourceHash;
    @Column(name = "meeting_id", nullable = false, updatable = false)
    private UUID meetingId;
    @Column(name = "capture_id", nullable = false, updatable = false)
    private UUID captureId;
    @Column(name = "source_tenant_id", nullable = false, updatable = false)
    private long sourceTenantId;
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;
    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;
    @Column(name = "actor_subject", nullable = false, updatable = false, length = 255)
    private String actorSubject;
    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private long actorUserId;
    @Column(name = "consent_version", nullable = false, updatable = false, length = 64)
    private String consentVersion;
    @Column(name = "consent_revision", nullable = false, updatable = false)
    private long consentRevision;
    @Column(name = "reason_code", nullable = false, updatable = false, length = 64)
    private String reasonCode;
    @Column(name = "correlation_id", updatable = false, length = 128)
    private String correlationId;
    @Column(name = "revoked_at", nullable = false, updatable = false)
    private Instant revokedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEventKey() { return eventKey; }
    public void setEventKey(String eventKey) { this.eventKey = eventKey; }
    public String getSourceHash() { return sourceHash; }
    public void setSourceHash(String sourceHash) { this.sourceHash = sourceHash; }
    public UUID getMeetingId() { return meetingId; }
    public void setMeetingId(UUID meetingId) { this.meetingId = meetingId; }
    public UUID getCaptureId() { return captureId; }
    public void setCaptureId(UUID captureId) { this.captureId = captureId; }
    public long getSourceTenantId() { return sourceTenantId; }
    public void setSourceTenantId(long sourceTenantId) { this.sourceTenantId = sourceTenantId; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public String getActorSubject() { return actorSubject; }
    public void setActorSubject(String actorSubject) { this.actorSubject = actorSubject; }
    public long getActorUserId() { return actorUserId; }
    public void setActorUserId(long actorUserId) { this.actorUserId = actorUserId; }
    public String getConsentVersion() { return consentVersion; }
    public void setConsentVersion(String consentVersion) { this.consentVersion = consentVersion; }
    public long getConsentRevision() { return consentRevision; }
    public void setConsentRevision(long consentRevision) { this.consentRevision = consentRevision; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
