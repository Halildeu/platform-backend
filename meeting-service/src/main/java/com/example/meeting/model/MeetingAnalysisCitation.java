package com.example.meeting.model;

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
 * Grounding citation for one {@link MeetingAnalysisRun} — #244 BE-1
 * acceptance condition 1 (citations[] is part of the ingestion payload).
 */
@Entity
@Table(name = "meeting_analysis_citations",
        indexes = {
                @Index(name = "idx_meeting_analysis_citations_meeting_id", columnList = "meeting_id"),
                @Index(name = "idx_meeting_analysis_citations_analysis_run_id", columnList = "analysis_run_id"),
                @Index(name = "idx_meeting_analysis_citations_org_id", columnList = "org_id")
        })
public class MeetingAnalysisCitation {

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

    @Column(name = "claim", nullable = false, length = 4000)
    private String claim;

    @Column(name = "source_index")
    private Integer sourceIndex;

    @Column(name = "source_text", length = 4000)
    private String sourceText;

    @Column(name = "similarity")
    private Double similarity;

    @Column(name = "grounded")
    private Boolean grounded;

    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "reason", length = 2000)
    private String reason;

    @Column(name = "start_sec")
    private Double startSec;

    @Column(name = "source_char_start")
    private Integer sourceCharStart;

    @Column(name = "source_char_end")
    private Integer sourceCharEnd;

    @Column(name = "source_hash", length = 128)
    private String sourceHash;

    @Column(name = "quote_hash", length = 128)
    private String quoteHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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

    public String getClaim() {
        return claim;
    }

    public void setClaim(String claim) {
        this.claim = claim;
    }

    public Integer getSourceIndex() {
        return sourceIndex;
    }

    public void setSourceIndex(Integer sourceIndex) {
        this.sourceIndex = sourceIndex;
    }

    public String getSourceText() {
        return sourceText;
    }

    public void setSourceText(String sourceText) {
        this.sourceText = sourceText;
    }

    public Double getSimilarity() {
        return similarity;
    }

    public void setSimilarity(Double similarity) {
        this.similarity = similarity;
    }

    public Boolean getGrounded() {
        return grounded;
    }

    public void setGrounded(Boolean grounded) {
        this.grounded = grounded;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Double getStartSec() {
        return startSec;
    }

    public void setStartSec(Double startSec) {
        this.startSec = startSec;
    }

    public Integer getSourceCharStart() {
        return sourceCharStart;
    }

    public void setSourceCharStart(Integer sourceCharStart) {
        this.sourceCharStart = sourceCharStart;
    }

    public Integer getSourceCharEnd() {
        return sourceCharEnd;
    }

    public void setSourceCharEnd(Integer sourceCharEnd) {
        this.sourceCharEnd = sourceCharEnd;
    }

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
    }

    public String getQuoteHash() {
        return quoteHash;
    }

    public void setQuoteHash(String quoteHash) {
        this.quoteHash = quoteHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MeetingAnalysisCitation that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
