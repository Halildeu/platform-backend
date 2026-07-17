package com.example.transcript.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A single transcript segment (one spoken line/utterance of a meeting).
 *
 * <p>KİŞİSEL VERİ: {@code textDraft}/{@code textFinal} carry meeting speech.
 * Every read/list/search/export of this data MUST be recorded in
 * {@code transcript_access_audit} (KVKK m.12) — see
 * {@code TranscriptSegmentService}.
 *
 * <p>{@code meetingId} / {@code sessionId} are CROSS-SERVICE UUID references
 * (meeting-service owns a separate DB schema) → plain columns, NO JPA relation
 * and NO foreign key.
 *
 * <p>org_id compat (V1, born canonical): {@code tenantId} NOT NULL +
 * {@code orgId} nullable. The canonical write path sets BOTH equal; the V1
 * trigger back-fills any null {@code orgId}; the V1 CHECK rejects a both-set
 * mismatch (23514). Read paths use {@link #getEffectiveOrgId()} so legacy null
 * rows still resolve.
 */
@Entity
@Table(name = "transcript_segments",
        indexes = {
                @Index(name = "idx_transcript_segments_org_id", columnList = "org_id"),
                @Index(name = "idx_transcript_segments_tenant_meeting",
                        columnList = "tenant_id,meeting_id,start_time"),
                @Index(name = "idx_transcript_segments_tenant_session",
                        columnList = "tenant_id,session_id"),
                @Index(name = "idx_transcript_segments_tenant_status",
                        columnList = "tenant_id,status"),
                @Index(name = "idx_transcript_segments_tenant_source_order",
                        columnList = "tenant_id,source_system,source_session_id,source_window_seq")
        })
public class TranscriptSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * org_id compat column (V1). Nullable in JPA; the V1 CHECK constraint
     * enforces {@code org_id IS NULL OR org_id = tenant_id}. The canonical
     * service write path sets this AND {@code tenantId} to the same UUID.
     */
    @Column(name = "org_id")
    private UUID orgId;

    /** Cross-service ref to meeting-service (separate schema; no FK). */
    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    /** Cross-service ref to the capture session (nullable; no FK). */
    @Column(name = "session_id")
    private UUID sessionId;

    /** Diarization speaker reference (opaque UUID; no FK). */
    @Column(name = "speaker_id")
    private UUID speakerId;

    /** Segment start offset, seconds from meeting start. */
    @Column(name = "start_time", nullable = false)
    private Double startTime;

    /** Segment end offset, seconds from meeting start ({@code >= startTime}). */
    @Column(name = "end_time", nullable = false)
    private Double endTime;

    /** Raw ASR draft text (KİŞİSEL VERİ). */
    @Column(name = "text_draft", columnDefinition = "text")
    private String textDraft;

    /** Corrected/finalized text (KİŞİSEL VERİ; nullable until finalized). */
    @Column(name = "text_final", columnDefinition = "text")
    private String textFinal;

    /** ASR confidence 0.0–1.0 (nullable). */
    @Column(name = "confidence")
    private Double confidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TranscriptSegmentStatus status = TranscriptSegmentStatus.DRAFT;

    /** Source system for machine-generated transcript rows, e.g. DIRECT_STT. */
    @Column(name = "source_system", length = 64)
    private String sourceSystem;

    /** Redis stream entry id / source event id; metadata only, no transcript text. */
    @Column(name = "source_event_id", length = 128)
    private String sourceEventId;

    /** Producer session id. audio-gateway direct-STT uses SES-* strings, not UUIDs. */
    @Column(name = "source_session_id", length = 128)
    private String sourceSessionId;

    /** Legacy chunk sequence alias. Direct-STT stores the source window's last chunk. */
    @Column(name = "source_chunk_seq")
    private Long sourceChunkSeq;

    /** Producer window sequence used as the Direct-STT replay identity. */
    @Column(name = "source_window_seq")
    private Long sourceWindowSeq;

    /** First admitted audio chunk included in the Direct-STT source window. */
    @Column(name = "source_first_chunk_seq")
    private Long sourceFirstChunkSeq;

    /** Last admitted audio chunk included in the Direct-STT source window. */
    @Column(name = "source_last_chunk_seq")
    private Long sourceLastChunkSeq;

    /** SHA-256 metadata for the accepted source audio window. No raw audio is stored here. */
    @Column(name = "source_sha256", length = 128)
    private String sourceSha256;

    /** PII-safe correlation id from the source path. */
    @Column(name = "source_correlation_id", length = 128)
    private String sourceCorrelationId;

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
        if (status == null) {
            status = TranscriptSegmentStatus.DRAFT;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        if (status == null) {
            status = TranscriptSegmentStatus.DRAFT;
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * org_id accessor. May be {@code null} on legacy rows; read paths should
     * use {@link #getEffectiveOrgId()}.
     */
    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    /**
     * Effective-org accessor: {@code orgId} when populated (canonical write
     * path) else {@code tenantId} (legacy rows). The V1 CHECK guarantees that
     * when {@code orgId} is non-null it equals {@code tenantId}, so the two
     * paths are observably indistinguishable downstream.
     */
    public UUID getEffectiveOrgId() {
        return orgId != null ? orgId : tenantId;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(UUID meetingId) {
        this.meetingId = meetingId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getSpeakerId() {
        return speakerId;
    }

    public void setSpeakerId(UUID speakerId) {
        this.speakerId = speakerId;
    }

    public Double getStartTime() {
        return startTime;
    }

    public void setStartTime(Double startTime) {
        this.startTime = startTime;
    }

    public Double getEndTime() {
        return endTime;
    }

    public void setEndTime(Double endTime) {
        this.endTime = endTime;
    }

    public String getTextDraft() {
        return textDraft;
    }

    public void setTextDraft(String textDraft) {
        this.textDraft = textDraft;
    }

    public String getTextFinal() {
        return textFinal;
    }

    public void setTextFinal(String textFinal) {
        this.textFinal = textFinal;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public TranscriptSegmentStatus getStatus() {
        return status;
    }

    public void setStatus(TranscriptSegmentStatus status) {
        this.status = status;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public void setSourceEventId(String sourceEventId) {
        this.sourceEventId = sourceEventId;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public void setSourceSessionId(String sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
    }

    public Long getSourceChunkSeq() {
        return sourceChunkSeq;
    }

    public void setSourceChunkSeq(Long sourceChunkSeq) {
        this.sourceChunkSeq = sourceChunkSeq;
    }

    public Long getSourceWindowSeq() {
        return sourceWindowSeq;
    }

    public void setSourceWindowSeq(Long sourceWindowSeq) {
        this.sourceWindowSeq = sourceWindowSeq;
    }

    public Long getSourceFirstChunkSeq() {
        return sourceFirstChunkSeq;
    }

    public void setSourceFirstChunkSeq(Long sourceFirstChunkSeq) {
        this.sourceFirstChunkSeq = sourceFirstChunkSeq;
    }

    public Long getSourceLastChunkSeq() {
        return sourceLastChunkSeq;
    }

    public void setSourceLastChunkSeq(Long sourceLastChunkSeq) {
        this.sourceLastChunkSeq = sourceLastChunkSeq;
    }

    public String getSourceSha256() {
        return sourceSha256;
    }

    public void setSourceSha256(String sourceSha256) {
        this.sourceSha256 = sourceSha256;
    }

    public String getSourceCorrelationId() {
        return sourceCorrelationId;
    }

    public void setSourceCorrelationId(String sourceCorrelationId) {
        this.sourceCorrelationId = sourceCorrelationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranscriptSegment that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
