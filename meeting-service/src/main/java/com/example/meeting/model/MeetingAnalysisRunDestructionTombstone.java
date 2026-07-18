package com.example.meeting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Metadata-only evidence for one analysis run destroyed by erasure or retention. */
@Entity
@Table(name = "meeting_analysis_run_destruction_tombstone")
public class MeetingAnalysisRunDestructionTombstone {

    @Id
    @Column(name = "analysis_run_id", nullable = false, updatable = false)
    private UUID analysisRunId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "meeting_id", nullable = false, updatable = false)
    private UUID meetingId;

    @Column(name = "session_id", updatable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 16, updatable = false)
    private MeetingAnalysisRunDestructionReason reason;

    @Column(name = "destroyed_at", nullable = false, updatable = false)
    private Instant destroyedAt;

    public UUID getAnalysisRunId() { return analysisRunId; }
    public void setAnalysisRunId(UUID value) { this.analysisRunId = value; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID value) { this.tenantId = value; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID value) { this.orgId = value; }
    public UUID getMeetingId() { return meetingId; }
    public void setMeetingId(UUID value) { this.meetingId = value; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID value) { this.sessionId = value; }
    public MeetingAnalysisRunDestructionReason getReason() { return reason; }
    public void setReason(MeetingAnalysisRunDestructionReason value) { this.reason = value; }
    public Instant getDestroyedAt() { return destroyedAt; }
    public void setDestroyedAt(Instant value) { this.destroyedAt = value; }
}
