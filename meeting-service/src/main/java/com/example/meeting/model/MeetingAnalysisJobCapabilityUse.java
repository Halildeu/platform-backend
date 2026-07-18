package com.example.meeting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** Metadata-only ledger proving that a short-lived job capability was consumed once. */
@Entity
@Table(name = "meeting_analysis_job_capability_uses",
        indexes = @Index(
                name = "idx_meeting_analysis_capability_uses_run",
                columnList = "analysis_run_id"))
public class MeetingAnalysisJobCapabilityUse implements Persistable<UUID> {

    @Id
    @Column(name = "capability_id", nullable = false, updatable = false)
    private UUID capabilityId;

    @Column(name = "analysis_run_id", nullable = false, updatable = false)
    private UUID analysisRunId;

    @Column(name = "consumed_at", nullable = false, updatable = false)
    private Instant consumedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @PrePersist
    void onCreate() {
        if (consumedAt == null) {
            consumedAt = Instant.now();
        }
    }

    public UUID getCapabilityId() {
        return capabilityId;
    }

    @Override
    public UUID getId() {
        return capabilityId;
    }

    /** Ledger rows are insert-only; duplicate IDs must surface as replay. */
    @Override
    public boolean isNew() {
        return true;
    }

    public void setCapabilityId(UUID capabilityId) {
        this.capabilityId = capabilityId;
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public void setAnalysisRunId(UUID analysisRunId) {
        this.analysisRunId = analysisRunId;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
