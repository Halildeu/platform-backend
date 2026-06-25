package com.example.transcript.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadata-only destruction audit for Faz 24 transcript retention.
 *
 * <p>The row proves KVKK m.7 deletion activity without retaining transcript
 * text, search terms, segment content, or accessor subject values.
 */
@Entity
@Table(name = "transcript_retention_destruction_audit",
        indexes = {
                @Index(name = "idx_transcript_retention_audit_layer_executed",
                        columnList = "layer_id,executed_at"),
                @Index(name = "idx_transcript_retention_audit_job_id", columnList = "job_id")
        })
public class TranscriptRetentionDestructionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "layer_id", nullable = false, length = 96)
    private String layerId;

    @Column(name = "cutoff_at", nullable = false)
    private Instant cutoffAt;

    @Column(name = "deleted_count", nullable = false)
    private long deletedCount;

    @Column(name = "job_id", nullable = false, length = 96)
    private String jobId;

    @Column(name = "audit_payload", nullable = false, length = 32)
    private String auditPayload = "metadata-only";

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @PrePersist
    void prePersist() {
        if (executedAt == null) {
            executedAt = Instant.now();
        }
        if (auditPayload == null) {
            auditPayload = "metadata-only";
        }
    }

    public UUID getId() {
        return id;
    }

    public String getLayerId() {
        return layerId;
    }

    public void setLayerId(String layerId) {
        this.layerId = layerId;
    }

    public Instant getCutoffAt() {
        return cutoffAt;
    }

    public void setCutoffAt(Instant cutoffAt) {
        this.cutoffAt = cutoffAt;
    }

    public long getDeletedCount() {
        return deletedCount;
    }

    public void setDeletedCount(long deletedCount) {
        this.deletedCount = deletedCount;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getAuditPayload() {
        return auditPayload;
    }

    public void setAuditPayload(String auditPayload) {
        this.auditPayload = auditPayload;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(Instant executedAt) {
        this.executedAt = executedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranscriptRetentionDestructionAudit that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
