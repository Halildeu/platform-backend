package com.example.transcript.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Content-free local projection of a canonical meeting-service session.
 *
 * <p>The source key is always tenant + meeting + source system + source session.
 * {@code sessionId} is populated only after the meeting-service resolver returns
 * the exact same scope.
 */
@Entity
@Table(name = "transcript_session_associations")
public class TranscriptSessionAssociation {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "meeting_id", nullable = false, updatable = false)
    private UUID meetingId;

    @Column(name = "source_system", nullable = false, length = 64, updatable = false)
    private String sourceSystem;

    @Column(name = "source_session_id", nullable = false, length = 128, updatable = false)
    private String sourceSessionId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TranscriptSessionAssociationStatus status;

    @Column(name = "resolution_attempts", nullable = false)
    private int resolutionAttempts;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "finalization_version", nullable = false)
    private long finalizationVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public TranscriptSessionAssociationStatus getStatus() {
        return status;
    }

    public int getResolutionAttempts() {
        return resolutionAttempts;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public long getFinalizationVersion() {
        return finalizationVersion;
    }

    public void setFinalizationVersion(long finalizationVersion) {
        this.finalizationVersion = finalizationVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
