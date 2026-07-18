package com.example.meeting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Append-only metadata-only evidence for erasure state transitions. */
@Entity
@Table(name = "meeting_session_erasure_audit")
public class MeetingSessionErasureAudit {
    @Id
    private UUID id;
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16, updatable = false)
    private MeetingSessionErasureStatus state;
    @Column(name = "local_deleted_count", nullable = false, updatable = false)
    private int localDeletedCount;
    @Column(name = "remote_deleted_count", nullable = false, updatable = false)
    private int remoteDeletedCount;
    @Column(name = "audit_payload", nullable = false, length = 32, updatable = false)
    private String auditPayload = "metadata-only";
    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
    public MeetingSessionErasureStatus getState() { return state; }
    public void setState(MeetingSessionErasureStatus state) { this.state = state; }
    public int getLocalDeletedCount() { return localDeletedCount; }
    public void setLocalDeletedCount(int localDeletedCount) { this.localDeletedCount = localDeletedCount; }
    public int getRemoteDeletedCount() { return remoteDeletedCount; }
    public void setRemoteDeletedCount(int remoteDeletedCount) { this.remoteDeletedCount = remoteDeletedCount; }
    public String getAuditPayload() { return auditPayload; }
    public void setAuditPayload(String auditPayload) { this.auditPayload = auditPayload; }
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
}
