package com.example.transcript.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Content-free permanent evidence that one Direct-STT source window was retained out. */
@Entity
@Table(name = "transcript_source_retention_fences")
public class TranscriptSourceRetentionFence {
    @Id
    private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;
    @Column(name = "org_id", updatable = false)
    private UUID orgId;
    @Column(name = "meeting_id", nullable = false, updatable = false)
    private UUID meetingId;
    @Column(name = "session_id", updatable = false)
    private UUID sessionId;
    @Column(name = "source_session_hash", nullable = false, length = 64, updatable = false)
    private String sourceSessionHash;
    @Column(name = "source_window_seq", nullable = false, updatable = false)
    private long sourceWindowSeq;
    @Column(name = "retained_at", nullable = false, updatable = false)
    private Instant retainedAt;

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
    public long getSourceWindowSeq() { return sourceWindowSeq; }
    public void setSourceWindowSeq(long sourceWindowSeq) { this.sourceWindowSeq = sourceWindowSeq; }
    public Instant getRetainedAt() { return retainedAt; }
    public void setRetainedAt(Instant retainedAt) { this.retainedAt = retainedAt; }
}
