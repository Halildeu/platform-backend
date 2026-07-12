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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadata-only KVKK m.12 audit row for one successful canonical result read.
 *
 * <p>The entity structurally cannot hold a summary, transcript, decision,
 * action, citation, source excerpt, prompt, request, response, or JSON payload.
 * The accessor and effective organization come from authenticated context,
 * never from request content.
 */
@Entity
@Table(name = "meeting_intelligence_result_access_audit",
        indexes = {
                @Index(name = "idx_meeting_result_access_tenant_accessed",
                        columnList = "tenant_id,accessed_at"),
                @Index(name = "idx_meeting_result_access_meeting_accessed",
                        columnList = "meeting_id,accessed_at"),
                @Index(name = "idx_meeting_result_access_retention",
                        columnList = "accessed_at,id")
        })
public class MeetingIntelligenceResultAccessAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "accessor_subject", nullable = false, length = 255)
    private String accessorSubject;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "analysis_run_id", nullable = false)
    private UUID analysisRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_type", nullable = false, length = 32)
    private MeetingIntelligenceResultAccessType accessType;

    @Column(name = "result_count", nullable = false)
    private int resultCount;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;

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

    public String getAccessorSubject() {
        return accessorSubject;
    }

    public void setAccessorSubject(String accessorSubject) {
        this.accessorSubject = accessorSubject;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(UUID meetingId) {
        this.meetingId = meetingId;
    }

    public UUID getAnalysisRunId() {
        return analysisRunId;
    }

    public void setAnalysisRunId(UUID analysisRunId) {
        this.analysisRunId = analysisRunId;
    }

    public MeetingIntelligenceResultAccessType getAccessType() {
        return accessType;
    }

    public void setAccessType(MeetingIntelligenceResultAccessType accessType) {
        this.accessType = accessType;
    }

    public int getResultCount() {
        return resultCount;
    }

    public void setResultCount(int resultCount) {
        this.resultCount = resultCount;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Instant getAccessedAt() {
        return accessedAt;
    }

    public void setAccessedAt(Instant accessedAt) {
        this.accessedAt = accessedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MeetingIntelligenceResultAccessAudit that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
