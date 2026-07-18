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

/** Permanent canonical/source-session privacy fence. */
@Entity
@Table(name = "transcript_session_erasure_tombstones")
public class TranscriptSessionErasureTombstone {
    @Id
    private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;
    @Column(name = "org_id", updatable = false)
    private UUID orgId;
    @Column(name = "meeting_id", nullable = false, updatable = false)
    private UUID meetingId;
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;
    @Column(name = "source_session_hash", length = 64, updatable = false)
    private String sourceSessionHash;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TranscriptSessionErasureStatus status;
    @Column(name = "segment_deleted_count", nullable = false)
    private int segmentDeletedCount;
    @Column(name = "finalization_deleted_count", nullable = false)
    private int finalizationDeletedCount;
    @Column(name = "association_deleted_count", nullable = false)
    private int associationDeletedCount;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getMeetingId() { return meetingId; }
    public void setMeetingId(UUID meetingId) { this.meetingId = meetingId; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
    public String getSourceSessionHash() { return sourceSessionHash; }
    public void setSourceSessionHash(String sourceSessionHash) { this.sourceSessionHash = sourceSessionHash; }
    public TranscriptSessionErasureStatus getStatus() { return status; }
    public void setStatus(TranscriptSessionErasureStatus status) { this.status = status; }
    public int getSegmentDeletedCount() { return segmentDeletedCount; }
    public void setSegmentDeletedCount(int value) { this.segmentDeletedCount = value; }
    public int getFinalizationDeletedCount() { return finalizationDeletedCount; }
    public void setFinalizationDeletedCount(int value) { this.finalizationDeletedCount = value; }
    public int getAssociationDeletedCount() { return associationDeletedCount; }
    public void setAssociationDeletedCount(int value) { this.associationDeletedCount = value; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
