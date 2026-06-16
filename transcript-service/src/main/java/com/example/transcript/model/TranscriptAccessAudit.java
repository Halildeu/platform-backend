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
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * KVKK Madde 12 access-log row: one record per access to transcript personal
 * data (kim / ne zaman / hangi segment / hangi tip / kaç kayıt).
 *
 * <p><b>TRANSCRIPT-FREE by contract.</b> This entity intentionally has NO field
 * for the segment text and NO field for the search term. It carries access
 * METADATA only ({@code segmentId}, {@code meetingId}, {@code resultCount},
 * {@code accessType}). Never add a {@code text} / {@code query} / {@code term}
 * column here — that would defeat the purpose of a privacy-safe access log.
 *
 * <p>Append-only: rows are inserted, never updated (no {@code @Version}, no
 * {@code updatedAt}, no setter for mutable state). Retention is 2 yıl, enforced
 * by the separate retention worker (#1250); this service is a WRITE-only
 * producer.
 *
 * <p>{@code accessorSubject} is the request authz principal, supplied by the
 * service from the resolved {@link com.example.transcript.security.AdminTenantContext}
 * — never from the request body.
 */
@Entity
@Table(name = "transcript_access_audit",
        indexes = {
                @Index(name = "idx_transcript_access_audit_org_id", columnList = "org_id"),
                @Index(name = "idx_transcript_access_audit_tenant_accessed",
                        columnList = "tenant_id,accessed_at")
        })
public class TranscriptAccessAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    /** KVKK m.12 "kim" — request authz principal (context-derived, NOT body). */
    @Column(name = "accessor_subject", nullable = false, length = 255)
    private String accessorSubject;

    /** Target segment id; nullable for LIST/SEARCH (meeting-scoped access). */
    @Column(name = "segment_id")
    private UUID segmentId;

    @Column(name = "meeting_id")
    private UUID meetingId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_type", nullable = false, length = 16)
    private TranscriptAccessType accessType;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;

    /** Rows returned by a LIST/SEARCH/EXPORT; null for a single READ. */
    @Column(name = "result_count")
    private Integer resultCount;

    @PrePersist
    void prePersist() {
        if (accessedAt == null) {
            accessedAt = Instant.now();
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

    public UUID getOrgId() {
        return orgId;
    }

    public void setOrgId(UUID orgId) {
        this.orgId = orgId;
    }

    public UUID getEffectiveOrgId() {
        return orgId != null ? orgId : tenantId;
    }

    public String getAccessorSubject() {
        return accessorSubject;
    }

    public void setAccessorSubject(String accessorSubject) {
        this.accessorSubject = accessorSubject;
    }

    public UUID getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(UUID segmentId) {
        this.segmentId = segmentId;
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

    public TranscriptAccessType getAccessType() {
        return accessType;
    }

    public void setAccessType(TranscriptAccessType accessType) {
        this.accessType = accessType;
    }

    public Instant getAccessedAt() {
        return accessedAt;
    }

    public void setAccessedAt(Instant accessedAt) {
        this.accessedAt = accessedAt;
    }

    public Integer getResultCount() {
        return resultCount;
    }

    public void setResultCount(Integer resultCount) {
        this.resultCount = resultCount;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TranscriptAccessAudit that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
