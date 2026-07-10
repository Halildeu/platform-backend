package com.example.meeting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A decision recorded for a {@link Meeting} (1:N) — Faz 24. See
 * {@link MeetingSession} for the flat-parent-FK + {@code ON DELETE
 * CASCADE} convention and {@link Meeting} for the org_id compat pattern.
 */
@Entity
@Table(name = "meeting_decisions",
        indexes = {
                @Index(name = "idx_meeting_decisions_meeting_id", columnList = "meeting_id"),
                @Index(name = "idx_meeting_decisions_org_id", columnList = "org_id")
        })
public class MeetingDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "detail", length = 4000)
    private String detail;

    @Column(name = "decided_by_subject", length = 255)
    private String decidedBySubject;

    /** Non-null only for a row inserted by #244 BE-1 analysis ingestion. */
    @Column(name = "analysis_run_id")
    private UUID analysisRunId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** Position within the analysis run's decisions[] — AI-ingested rows only. */
    @Column(name = "ordinal")
    private Integer ordinal;

    /** e.g. {@code "AI_ANALYSIS"} — null for manually-entered decisions. */
    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "created_by_subject", nullable = false, length = 255)
    private String createdBySubject;

    @Column(name = "last_updated_by_subject", nullable = false, length = 255)
    private String lastUpdatedBySubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(UUID meetingId) {
        this.meetingId = meetingId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public UUID getEffectiveOrgId() {
        return orgId != null ? orgId : tenantId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getDecidedBySubject() {
        return decidedBySubject;
    }

    public void setDecidedBySubject(String decidedBySubject) {
        this.decidedBySubject = decidedBySubject;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public void setAnalysisRunId(UUID analysisRunId) {
        this.analysisRunId = analysisRunId;
    }

    public Integer getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(Integer ordinal) {
        this.ordinal = ordinal;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCreatedBySubject() {
        return createdBySubject;
    }

    public void setCreatedBySubject(String createdBySubject) {
        this.createdBySubject = createdBySubject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getLastUpdatedBySubject() {
        return lastUpdatedBySubject;
    }

    public void setLastUpdatedBySubject(String lastUpdatedBySubject) {
        this.lastUpdatedBySubject = lastUpdatedBySubject;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MeetingDecision that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
