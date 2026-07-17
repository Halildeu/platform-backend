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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A recorded / live session belonging to a {@link Meeting} (1:N) — Faz 24.
 *
 * <p>The parent FK is stored as a plain {@code meetingId} UUID column
 * (not a JPA {@code @ManyToOne}) to keep the sub-resource queries flat
 * and tenant-scoped without an obligatory parent join — consistent with
 * the endpoint-admin per-table tenant-column convention. DB-level
 * {@code ON DELETE CASCADE} (V1) removes sessions when the meeting is
 * deleted; the service additionally deletes children explicitly so the
 * JPA persistence context stays consistent.
 *
 * <p>org_id compat: see {@link Meeting} for the {@code tenantId} /
 * {@code orgId} / {@link #getEffectiveOrgId()} pattern.
 */
@Entity
@Table(name = "meeting_sessions",
        indexes = {
                @Index(name = "idx_meeting_sessions_meeting_id", columnList = "meeting_id"),
                @Index(name = "idx_meeting_sessions_org_id", columnList = "org_id")
        })
public class MeetingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "session_label", length = 256)
    private String sessionLabel;

    @Column(name = "external_session_id", length = 128)
    private String externalSessionId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "recording_uri", length = 2048)
    private String recordingUri;

    @Enumerated(EnumType.STRING)
    @Column(name = "transcript_status", nullable = false, length = 32)
    private TranscriptStatus transcriptStatus = TranscriptStatus.PENDING;

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

    public String getSessionLabel() {
        return sessionLabel;
    }

    public void setSessionLabel(String sessionLabel) {
        this.sessionLabel = sessionLabel;
    }

    public String getExternalSessionId() {
        return externalSessionId;
    }

    public void setExternalSessionId(String externalSessionId) {
        this.externalSessionId = externalSessionId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public String getRecordingUri() {
        return recordingUri;
    }

    public void setRecordingUri(String recordingUri) {
        this.recordingUri = recordingUri;
    }

    public TranscriptStatus getTranscriptStatus() {
        return transcriptStatus;
    }

    public void setTranscriptStatus(TranscriptStatus transcriptStatus) {
        this.transcriptStatus = transcriptStatus;
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
        if (!(o instanceof MeetingSession that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
