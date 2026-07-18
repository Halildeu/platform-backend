package com.example.transcript.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transcript_session_erasure_audit")
public class TranscriptSessionErasureAudit {
    @Id
    private UUID id;
    @Column(name = "tombstone_id", nullable = false, updatable = false)
    private UUID tombstoneId;
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16, updatable = false)
    private TranscriptSessionErasureStatus state;
    @Column(name = "segment_deleted_count", nullable = false, updatable = false)
    private int segmentDeletedCount;
    @Column(name = "finalization_deleted_count", nullable = false, updatable = false)
    private int finalizationDeletedCount;
    @Column(name = "association_deleted_count", nullable = false, updatable = false)
    private int associationDeletedCount;
    @Column(name = "audit_payload", nullable = false, length = 32, updatable = false)
    private String auditPayload = "metadata-only";
    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTombstoneId() { return tombstoneId; }
    public void setTombstoneId(UUID tombstoneId) { this.tombstoneId = tombstoneId; }
    public TranscriptSessionErasureStatus getState() { return state; }
    public void setState(TranscriptSessionErasureStatus state) { this.state = state; }
    public int getSegmentDeletedCount() { return segmentDeletedCount; }
    public void setSegmentDeletedCount(int value) { this.segmentDeletedCount = value; }
    public int getFinalizationDeletedCount() { return finalizationDeletedCount; }
    public void setFinalizationDeletedCount(int value) { this.finalizationDeletedCount = value; }
    public int getAssociationDeletedCount() { return associationDeletedCount; }
    public void setAssociationDeletedCount(int value) { this.associationDeletedCount = value; }
    public String getAuditPayload() { return auditPayload; }
    public void setAuditPayload(String auditPayload) { this.auditPayload = auditPayload; }
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
}
