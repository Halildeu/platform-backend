package com.example.meeting.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A single meeting-ai analysis result, persisted by meeting-service as the system
 * of record (Faz 24, platform-ai#244 BE-1).
 *
 * <p><b>Identity equals idempotency.</b> There is no separate surrogate key: the
 * primary key {@code analysisRunId} is the caller's {@code Idempotency-Key}. A
 * retried ingestion therefore addresses exactly the row it created, and a child
 * row can only reference the thing the caller retried on.
 *
 * <p>A re-analysis is never an overwrite: it is a new run that points at the one
 * it replaces via {@link #supersedesAnalysisRunId}. Submitting the same
 * {@code analysisRunId} with a different {@link #payloadHash} is a conflict, not
 * an update — the service layer rejects it with {@code 409 IDEMPOTENCY_CONFLICT}.
 *
 * <p>{@link #transcriptSha256} identifies <em>which</em> transcript snapshot was
 * analysed. Canonical rows created since V8 also persist the immutable occurrence
 * tuple: session, positive finalization version, finalized-at and analysis spec.
 * Visibility orders occurrences by finalized-at and then finalization version, so
 * a late older occurrence cannot supersede a newer accepted result. Legacy rows
 * keep a null occurrence tuple and remain readable during the migration window.
 *
 * @see Meeting for the org_id compat pattern and the composite tenant-FK convention
 */
@Entity
@Table(name = "meeting_analysis_runs",
        indexes = {
                @Index(name = "idx_meeting_analysis_runs_meeting_id", columnList = "meeting_id"),
                @Index(name = "idx_meeting_analysis_runs_org_id", columnList = "org_id")
        })
public class MeetingAnalysisRun {

    /** The caller's Idempotency-Key; assigned by the producer, never generated here. */
    @Id
    @Column(name = "analysis_run_id", nullable = false, updatable = false)
    private UUID analysisRunId;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "transcript_session_id", nullable = false, length = 64)
    private String transcriptSessionId;

    @Column(name = "transcript_sha256", nullable = false, length = 64)
    private String transcriptSha256;

    /** Nullable only for rows created before V8; all new canonical writes set the tuple. */
    @Column(name = "finalization_version")
    private Long finalizationVersion;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "analysis_spec_version", length = 64)
    private String analysisSpecVersion;

    /** Metadata-only replay identity. The signed capability itself is never persisted. */
    @Column(name = "job_capability_id")
    private UUID jobCapabilityId;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;

    @Column(name = "analyzer_contract_version", nullable = false, length = 64)
    private String analyzerContractVersion;

    @Column(name = "model", length = 128)
    private String model;

    @Column(name = "backend", length = 64)
    private String backend;

    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "summary", columnDefinition = "text")
    private String summary;

    @Column(name = "summary_grounding_status", length = 32)
    private String summaryGroundingStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_citations", nullable = false)
    private String summaryCitations = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", nullable = false)
    private String citations = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rejected_claims", nullable = false)
    private String rejectedClaims = "[]";

    @Column(name = "ungrounded_count", nullable = false)
    private int ungroundedCount;

    @Column(name = "redacted", nullable = false)
    private boolean redacted;

    @Column(name = "redaction_count", nullable = false)
    private int redactionCount;

    /** Append-only: set at insert, cleared only by ON DELETE SET NULL. DB trigger enforces. */
    @Column(name = "supersedes_analysis_run_id", updatable = false)
    private UUID supersedesAnalysisRunId;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        final Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public void setAnalysisRunId(final UUID analysisRunId) {
        this.analysisRunId = analysisRunId;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(final UUID meetingId) {
        this.meetingId = meetingId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(final UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(final UUID orgId) {
        this.orgId = orgId;
    }

    public String getTranscriptSessionId() {
        return transcriptSessionId;
    }

    public void setTranscriptSessionId(final String transcriptSessionId) {
        this.transcriptSessionId = transcriptSessionId;
    }

    public String getTranscriptSha256() {
        return transcriptSha256;
    }

    public void setTranscriptSha256(final String transcriptSha256) {
        this.transcriptSha256 = transcriptSha256;
    }

    public Long getFinalizationVersion() { return finalizationVersion; }
    public void setFinalizationVersion(Long value) { this.finalizationVersion = value; }
    public Instant getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(Instant value) { this.finalizedAt = value; }
    public String getAnalysisSpecVersion() { return analysisSpecVersion; }
    public void setAnalysisSpecVersion(String value) { this.analysisSpecVersion = value; }
    public UUID getJobCapabilityId() { return jobCapabilityId; }
    public void setJobCapabilityId(UUID value) { this.jobCapabilityId = value; }
    public boolean isLegalHold() { return legalHold; }
    public void setLegalHold(boolean value) { this.legalHold = value; }

    public String getAnalyzerContractVersion() {
        return analyzerContractVersion;
    }

    public void setAnalyzerContractVersion(final String analyzerContractVersion) {
        this.analyzerContractVersion = analyzerContractVersion;
    }

    public String getModel() {
        return model;
    }

    public void setModel(final String model) {
        this.model = model;
    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(final String backend) {
        this.backend = backend;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(final String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public void setPayloadHash(final String payloadHash) {
        this.payloadHash = payloadHash;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(final String summary) {
        this.summary = summary;
    }

    public String getSummaryGroundingStatus() {
        return summaryGroundingStatus;
    }

    public void setSummaryGroundingStatus(final String summaryGroundingStatus) {
        this.summaryGroundingStatus = summaryGroundingStatus;
    }

    public String getSummaryCitations() {
        return summaryCitations;
    }

    public void setSummaryCitations(final String summaryCitations) {
        this.summaryCitations = summaryCitations;
    }

    public String getCitations() {
        return citations;
    }

    public void setCitations(final String citations) {
        this.citations = citations;
    }

    public String getRejectedClaims() {
        return rejectedClaims;
    }

    public void setRejectedClaims(final String rejectedClaims) {
        this.rejectedClaims = rejectedClaims;
    }

    public int getUngroundedCount() {
        return ungroundedCount;
    }

    public void setUngroundedCount(final int ungroundedCount) {
        this.ungroundedCount = ungroundedCount;
    }

    public boolean isRedacted() {
        return redacted;
    }

    public void setRedacted(final boolean redacted) {
        this.redacted = redacted;
    }

    public int getRedactionCount() {
        return redactionCount;
    }

    public void setRedactionCount(final int redactionCount) {
        this.redactionCount = redactionCount;
    }

    public UUID getSupersedesAnalysisRunId() {
        return supersedesAnalysisRunId;
    }

    public void setSupersedesAnalysisRunId(final UUID supersedesAnalysisRunId) {
        this.supersedesAnalysisRunId = supersedesAnalysisRunId;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(final Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MeetingAnalysisRun that)) {
            return false;
        }
        return analysisRunId != null && analysisRunId.equals(that.analysisRunId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(analysisRunId);
    }
}
