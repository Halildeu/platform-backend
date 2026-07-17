package com.example.auditconsumer.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Durable append-only ownership proof for a recorder consent capture. */
@Entity
@Immutable
@Table(name = "recording_consent_grant")
public class RecordingConsentGrant {

    @Id
    private UUID id;
    @Column(name = "event_key", nullable = false, unique = true, updatable = false, length = 200)
    private String eventKey;
    @Column(name = "source_hash", nullable = false, updatable = false, length = 64)
    private String sourceHash;
    @Column(name = "meeting_id", nullable = false, updatable = false)
    private UUID meetingId;
    @Column(name = "capture_id", nullable = false, unique = true, updatable = false)
    private UUID captureId;
    @Column(name = "source_tenant_id", nullable = false, updatable = false)
    private long sourceTenantId;
    @Column(name = "tenant_id", updatable = false)
    private UUID tenantId;
    @Column(name = "org_id", updatable = false)
    private UUID orgId;
    @Column(name = "actor_subject", nullable = false, updatable = false, length = 255)
    private String actorSubject;
    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private long actorUserId;
    @Column(name = "consent_version", nullable = false, updatable = false, length = 64)
    private String consentVersion;
    @Column(name = "consent_text_hash", nullable = false, updatable = false, length = 71)
    private String consentTextHash;
    @Column(name = "locale", nullable = false, updatable = false, length = 10)
    private String locale;
    @Column(name = "consent_revision", nullable = false, updatable = false)
    private long consentRevision;
    @Column(name = "correlation_id", updatable = false, length = 128)
    private String correlationId;
    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

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
    public String getConsentTextHash() { return consentTextHash; }
    public void setConsentTextHash(String consentTextHash) { this.consentTextHash = consentTextHash; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public long getConsentRevision() { return consentRevision; }
    public void setConsentRevision(long consentRevision) { this.consentRevision = consentRevision; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public Instant getGrantedAt() { return grantedAt; }
    public void setGrantedAt(Instant grantedAt) { this.grantedAt = grantedAt; }
}
