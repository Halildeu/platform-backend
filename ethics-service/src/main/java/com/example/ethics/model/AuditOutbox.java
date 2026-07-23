package com.example.ethics.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="ethics_audit_outbox")
public class AuditOutbox {
    @Id private UUID id;
    @Column(name="org_id", nullable=false) private UUID orgId;
    @Column(name="aggregate_id", nullable=false) private UUID aggregateId;
    @Column(name="event_type", nullable=false) private String eventType;
    @Column(nullable=false, length=4000) private String payload;
    @Column(nullable=false) private String status;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="attempt_count", nullable=false) private int attemptCount;
    @Column(name="next_attempt_at") private Instant nextAttemptAt;
    @Column(name="claim_token") private UUID claimToken;
    @Column(name="locked_until") private Instant lockedUntil;
    @Column(name="delivered_at") private Instant deliveredAt;
    @Column(name="last_error_code") private String lastErrorCode;
    protected AuditOutbox() {}
    public AuditOutbox(UUID id, UUID orgId, UUID aggregateId, String eventType, String payload, Instant now){this.id=id;this.orgId=orgId;this.aggregateId=aggregateId;this.eventType=eventType;this.payload=payload;this.status="PENDING";this.createdAt=now;}
    public UUID getId() { return id; }
    public UUID getOrgId() { return orgId; }
    public UUID getAggregateId() { return aggregateId; }
    public String getPayload() { return payload; }
    public String getEventType() { return eventType; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public int getAttemptCount() { return attemptCount; }
    public UUID getClaimToken() { return claimToken; }
    public Instant getLockedUntil() { return lockedUntil; }
}
