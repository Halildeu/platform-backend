package com.example.meeting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The summary text for one {@link MeetingAnalysisRun} — #244 BE-1. Did not
 * exist before this migration; {@link MeetingDecision}/{@link MeetingAction}
 * already had a table each, summary did not (see #413's finding that report
 * tables had nothing to read).
 *
 * <p>One row per analysis run (not per meeting) — history is kept by keeping
 * superseded runs' summary rows in place; "the summary for a meeting" is
 * always resolved via the meeting's current {@code CANONICAL}
 * {@link MeetingAnalysisRun}, never by a mutable per-meeting row.
 */
@Entity
@Table(name = "meeting_summaries",
        indexes = {
                @Index(name = "idx_meeting_summaries_meeting_id", columnList = "meeting_id"),
                @Index(name = "idx_meeting_summaries_org_id", columnList = "org_id")
        })
public class MeetingSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "analysis_run_id", nullable = false)
    private UUID analysisRunId;

    @Column(name = "summary_text", nullable = false, length = 8000)
    private String summaryText;

    @Column(name = "grounding_status", length = 32)
    private String groundingStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
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

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public void setAnalysisRunId(UUID analysisRunId) {
        this.analysisRunId = analysisRunId;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }

    public String getGroundingStatus() {
        return groundingStatus;
    }

    public void setGroundingStatus(String groundingStatus) {
        this.groundingStatus = groundingStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MeetingSummary that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
