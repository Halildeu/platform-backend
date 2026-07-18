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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A transactional-outbox row for a meeting analysis domain event — Faz 24
 * (platform-ai#244 BE-1d). Written in the SAME transaction as the
 * {@link MeetingAnalysisRun} and its children (atomic), then delivered by a
 * SEPARATE poller that reads only committed rows (commit-after-emit).
 *
 * <p><b>Exactly-once.</b> {@link #eventKey} is deterministic
 * ({@code <run>|<type>[|<ordinal>]}) and UNIQUE, so a retried or raced ingestion
 * can never create a duplicate event; a consumer that de-dups on {@code eventKey}
 * applies each side-effect once.
 *
 * <p><b>Thin event.</b> {@link #payload} carries identifiers + minimal metadata,
 * never the summary/transcript text — the consumer fetches the canonical result
 * from meeting-service. See {@code V4__meeting_event_outbox.sql}.
 */
@Entity
@Table(name = "meeting_event_outbox",
        indexes = {
                @Index(name = "idx_meeting_event_outbox_aggregate", columnList = "aggregate_id"),
                @Index(name = "idx_meeting_event_outbox_org_id", columnList = "org_id")
        })
public class MeetingEventOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Canonical dotted event name (e.g. {@code meeting.summary.ready}); see {@link MeetingEventType#wireValue()}. */
    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    /** Producer-owned aggregate scope (for example {@code meeting.recording}). */
    @Column(name = "aggregate_type", nullable = false, length = 64, updatable = false)
    private String aggregateType;

    /** The producing analysis run or recording session id, scoped by {@link #aggregateType}. */
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    /** Stable occurrence revision used in the deterministic event key. */
    @Column(name = "aggregate_revision", nullable = false, updatable = false)
    private long aggregateRevision;

    @Column(name = "meeting_id", nullable = false, updatable = false)
    private UUID meetingId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "org_id")
    private UUID orgId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    /** Canonical serializer output preserved outside JSONB normalization. */
    @Column(name = "payload_raw", columnDefinition = "text", updatable = false)
    private String payloadRaw;

    /** Deterministic idempotency key; UNIQUE. */
    @Column(name = "event_key", nullable = false, length = 200, updatable = false)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MeetingEventOutboxStatus status = MeetingEventOutboxStatus.PENDING;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "processing_owner", length = 128)
    private String processingOwner;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Exception class name of the last publish failure — never payload text. */
    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

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

    // ────────────────────────── getters / setters ──────────────────────────

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(final String eventType) {
        this.eventType = eventType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(final String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public long getAggregateRevision() {
        return aggregateRevision;
    }

    public void setAggregateRevision(final long aggregateRevision) {
        this.aggregateRevision = aggregateRevision;
    }

    public void setAggregateId(final UUID aggregateId) {
        this.aggregateId = aggregateId;
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

    public String getPayload() {
        return payload;
    }

    public void setPayload(final String payload) {
        this.payload = payload;
    }

    public String getPayloadRaw() {
        return payloadRaw;
    }

    public void setPayloadRaw(final String payloadRaw) {
        this.payloadRaw = payloadRaw;
    }

    public String getEventKey() {
        return eventKey;
    }

    public void setEventKey(final String eventKey) {
        this.eventKey = eventKey;
    }

    public MeetingEventOutboxStatus getStatus() {
        return status;
    }

    public void setStatus(final MeetingEventOutboxStatus status) {
        this.status = status;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(final UUID claimToken) {
        this.claimToken = claimToken;
    }

    public String getProcessingOwner() {
        return processingOwner;
    }

    public void setProcessingOwner(final String processingOwner) {
        this.processingOwner = processingOwner;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(final Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(final Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(final int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(final String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(final Instant publishedAt) {
        this.publishedAt = publishedAt;
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
        if (!(other instanceof MeetingEventOutbox that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
