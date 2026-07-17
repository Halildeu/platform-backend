package com.example.transcript.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One immutable canonical transcript finalization occurrence. */
@Entity
@Table(name = "transcript_finalizations")
public class TranscriptFinalization {

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

    @Column(name = "finalization_version", nullable = false, updatable = false)
    private long finalizationVersion;

    @Column(name = "segment_count", nullable = false, updatable = false)
    private int segmentCount;

    /** Local integrity metadata only; never copied into the meeting event. */
    @Column(name = "snapshot_sha256", nullable = false, length = 64, updatable = false)
    private String snapshotSha256;

    @Column(name = "finalized_at", nullable = false, updatable = false)
    private Instant finalizedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
    public long getFinalizationVersion() { return finalizationVersion; }
    public void setFinalizationVersion(long value) { this.finalizationVersion = value; }
    public int getSegmentCount() { return segmentCount; }
    public void setSegmentCount(int segmentCount) { this.segmentCount = segmentCount; }
    public String getSnapshotSha256() { return snapshotSha256; }
    public void setSnapshotSha256(String snapshotSha256) { this.snapshotSha256 = snapshotSha256; }
    public Instant getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(Instant finalizedAt) { this.finalizedAt = finalizedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
