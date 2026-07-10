package com.example.meeting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One row per accepted {@code analysisRunId} — #244 BE-1 (Verdict A: single
 * atomic aggregate-ingestion contract for meeting-ai analysis results).
 *
 * <p>Two independent unique constraints back the idempotency contract
 * (see the V3 migration): {@code analysis_run_id} (the operator-supplied
 * Idempotency-Key) and {@code (meeting_id, transcript_revision,
 * analyzer_contract_version)} (catches a retry that regenerated a new
 * run id for the same logical analysis). A partial unique index enforces
 * at most one {@link MeetingAnalysisRunStatus#CANONICAL} row per meeting —
 * re-analysis flips the previous canonical row to {@code SUPERSEDED} in the
 * same transaction that inserts the new canonical row, so "stale analysis
 * cannot overwrite a newer canonical one" is a DB-level invariant, not just
 * an application check.
 */
@Entity
@Table(name = "meeting_analysis_runs",
        indexes = {
                @Index(name = "idx_meeting_analysis_runs_meeting_id", columnList = "meeting_id"),
                @Index(name = "idx_meeting_analysis_runs_org_id", columnList = "org_id")
        })
public class MeetingAnalysisRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "analysis_run_id", nullable = false, length = 255)
    private String analysisRunId;

    @Column(name = "transcript_id", nullable = false, length = 255)
    private String transcriptId;

    @Column(name = "transcript_revision", nullable = false, length = 255)
    private String transcriptRevision;

    @Column(name = "analyzer_contract_version", nullable = false, length = 64)
    private String analyzerContractVersion;

    @Column(name = "model_version", length = 255)
    private String modelVersion;

    @Column(name = "prompt_version", length = 255)
    private String promptVersion;

    @Column(name = "payload_hash", nullable = false, length = 128)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MeetingAnalysisRunStatus status = MeetingAnalysisRunStatus.CANONICAL;

    @Column(name = "superseded_by_analysis_run_id", length = 255)
    private String supersededByAnalysisRunId;

    @Column(name = "supersedes_analysis_run_id", length = 255)
    private String supersedesAnalysisRunId;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

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

    public String getAnalysisRunId() {
        return analysisRunId;
    }

    public void setAnalysisRunId(String analysisRunId) {
        this.analysisRunId = analysisRunId;
    }

    public String getTranscriptId() {
        return transcriptId;
    }

    public void setTranscriptId(String transcriptId) {
        this.transcriptId = transcriptId;
    }

    public String getTranscriptRevision() {
        return transcriptRevision;
    }

    public void setTranscriptRevision(String transcriptRevision) {
        this.transcriptRevision = transcriptRevision;
    }

    public String getAnalyzerContractVersion() {
        return analyzerContractVersion;
    }

    public void setAnalyzerContractVersion(String analyzerContractVersion) {
        this.analyzerContractVersion = analyzerContractVersion;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public MeetingAnalysisRunStatus getStatus() {
        return status;
    }

    public void setStatus(MeetingAnalysisRunStatus status) {
        this.status = status;
    }

    public String getSupersededByAnalysisRunId() {
        return supersededByAnalysisRunId;
    }

    public void setSupersededByAnalysisRunId(String supersededByAnalysisRunId) {
        this.supersededByAnalysisRunId = supersededByAnalysisRunId;
    }

    public String getSupersedesAnalysisRunId() {
        return supersedesAnalysisRunId;
    }

    public void setSupersedesAnalysisRunId(String supersedesAnalysisRunId) {
        this.supersedesAnalysisRunId = supersedesAnalysisRunId;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
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
        if (!(o instanceof MeetingAnalysisRun that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
