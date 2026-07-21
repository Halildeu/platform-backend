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
    protected AuditOutbox() {}
    public AuditOutbox(UUID id, UUID orgId, UUID aggregateId, String eventType, String payload, Instant now){this.id=id;this.orgId=orgId;this.aggregateId=aggregateId;this.eventType=eventType;this.payload=payload;this.status="PENDING";this.createdAt=now;}
    public String getPayload() { return payload; }
    public String getEventType() { return eventType; }
}
