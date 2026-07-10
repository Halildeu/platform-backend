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
 * Transactional outbox row for a domain event emitted alongside an analysis
 * ingestion — #244 BE-1, unblocks backend#412's {@code summary.ready} /
 * {@code action.assigned} events.
 *
 * <p>Written in the SAME DB transaction as the {@link MeetingAnalysisRun} /
 * {@link MeetingSummary} / decision / action rows it describes (the
 * "commit-sonrası emit" acceptance requirement is satisfied by construction:
 * a consumer/poller only sees a row after the whole transaction — including
 * this insert — has committed; there is no separate emit step that could run
 * before or independently of the persist). {@code publishedAt} is left for a
 * follow-up outbox poller (mirrors the existing pattern in permission-service
 * {@code OutboxPoller} / notification-orchestrator {@code OutboxPoller}) —
 * out of scope for BE-1, which only guarantees the row exists durably and
 * atomically; publishing it is a separate concern.
 */
@Entity
@Table(name = "meeting_analysis_outbox_events",
        indexes = {
                @Index(name = "idx_meeting_analysis_outbox_events_org_id", columnList = "org_id")
        })
public class MeetingAnalysisOutboxEvent {

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

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    // Deliberately NOT @Lob: Hibernate maps @Lob String to Postgres OID
    // (large object) by default, which doesn't match the plain TEXT column
    // the V3 migration creates. A plain string mapping is correct for TEXT.
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

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

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MeetingAnalysisOutboxEvent that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
