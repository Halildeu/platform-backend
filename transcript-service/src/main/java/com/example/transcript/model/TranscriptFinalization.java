package com.example.transcript.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One immutable canonical transcript finalization occurrence. */
@Entity
@Table(name = "transcript_finalizations")
public class TranscriptFinalization {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "org_id", updatable = false)
    private UUID orgId;

    @Column(name = "meeting_id", nullable = false, updatable = false)
    private UUID meetingId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "finalization_version", nullable = false, updatable = false)
    private long finalizationVersion;

    /** Producer-owned authorization identity emitted only with this occurrence. */
    @Column(name = "analysis_run_id", updatable = false)
    private UUID analysisRunId;

    @Column(name = "segment_count", nullable = false, updatable = false)
    private int segmentCount;

    /** Local integrity metadata only; never copied into the meeting event. */
    @Column(name = "snapshot_sha256", nullable = false, length = 64, updatable = false)
    private String snapshotSha256;

    /** Exact selected transcript text for this occurrence (personal data). */
    @Column(name = "canonical_transcript", columnDefinition = "text", updatable = false)
    private String canonicalTranscript;

    @Column(name = "canonical_transcript_sha256", length = 64, updatable = false)
    private String canonicalTranscriptSha256;

    /** Exact ordered selected segment projection for this occurrence (personal data). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "canonical_segments", updatable = false)
    private String canonicalSegments;

    @Column(name = "canonical_projection_sha256", length = 64, updatable = false)
    private String canonicalProjectionSha256;

    @Column(name = "finalized_at", nullable = false, updatable = false)
    private Instant finalizedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getOrgId() { return orgId; }
    public void setOrgId(UUID orgId) { this.orgId = orgId; }
    public UUID getMeetingId() { return meetingId; }
    public void setMeetingId(UUID meetingId) { this.meetingId = meetingId; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
    public long getFinalizationVersion() { return finalizationVersion; }
    public void setFinalizationVersion(long value) { this.finalizationVersion = value; }
    public UUID getAnalysisRunId() { return analysisRunId; }
    public void setAnalysisRunId(UUID analysisRunId) { this.analysisRunId = analysisRunId; }
    public int getSegmentCount() { return segmentCount; }
    public void setSegmentCount(int segmentCount) { this.segmentCount = segmentCount; }
    public String getSnapshotSha256() { return snapshotSha256; }
    public void setSnapshotSha256(String snapshotSha256) { this.snapshotSha256 = snapshotSha256; }
    public String getCanonicalTranscript() { return canonicalTranscript; }
    public void setCanonicalTranscript(String canonicalTranscript) {
        this.canonicalTranscript = canonicalTranscript;
    }
    public String getCanonicalTranscriptSha256() { return canonicalTranscriptSha256; }
    public void setCanonicalTranscriptSha256(String canonicalTranscriptSha256) {
        this.canonicalTranscriptSha256 = canonicalTranscriptSha256;
    }
    public String getCanonicalSegments() { return canonicalSegments; }
    public void setCanonicalSegments(String canonicalSegments) {
        this.canonicalSegments = canonicalSegments;
    }
    public String getCanonicalProjectionSha256() { return canonicalProjectionSha256; }
    public void setCanonicalProjectionSha256(String canonicalProjectionSha256) {
        this.canonicalProjectionSha256 = canonicalProjectionSha256;
    }
    public Instant getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(Instant finalizedAt) { this.finalizedAt = finalizedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public boolean isLegalHold() { return legalHold; }
    public void setLegalHold(boolean legalHold) { this.legalHold = legalHold; }
}
