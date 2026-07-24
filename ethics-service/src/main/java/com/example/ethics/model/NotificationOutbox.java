package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Case-independent, no-PII notification signal.
 *
 * <p>There is deliberately no case/report/message/receipt identifier and no
 * free-form payload column in this table. A provider outage therefore leaves a
 * durable operational signal without creating a second narrative or identity
 * store.
 */
@Entity
@Table(name = "ethics_notification_outbox")
public class NotificationOutbox {
    @Id
    private UUID id;
    @Column(name = "org_id", nullable = false)
    private UUID orgId;
    @Column(name = "event_type", nullable = false)
    private String eventType;
    @Column(nullable = false)
    private String status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "claim_token")
    private UUID claimToken;
    @Column(name = "locked_until")
    private Instant lockedUntil;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Column(name = "last_error_code")
    private String lastErrorCode;

    protected NotificationOutbox() {
    }

    public NotificationOutbox(UUID id, UUID orgId, String eventType, Instant createdAt) {
        this.id = id;
        this.orgId = orgId;
        this.eventType = eventType;
        this.status = "PENDING";
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrgId() { return orgId; }
    public String getEventType() { return eventType; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public int getAttemptCount() { return attemptCount; }
    public UUID getClaimToken() { return claimToken; }
    public Instant getLockedUntil() { return lockedUntil; }
}
