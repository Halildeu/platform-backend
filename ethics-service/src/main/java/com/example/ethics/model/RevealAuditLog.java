package com.example.ethics.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Faz 35 ES-303 WORM audit trail — write-once record for each reveal-workflow
 * event. The repository intentionally exposes only append and read; no update
 * or delete method is defined. Long-term retention rotation (off-cluster archive)
 * is scheduled by ES-311 owner-approval package (Vault snapshot + PG dump +
 * S3 archive).
 */
@Entity @Table(name="reveal_audit_log")
public class RevealAuditLog {
    @Id private UUID id;
    @Column(name="request_id", nullable=false) private UUID requestId;
    @Column(name="case_id", nullable=false) private UUID caseId;
    @Column(name="event_type", nullable=false) private String eventType;
    @Column(name="actor_subject", nullable=false) private String actorSubject;
    @Column(name="actor_role", nullable=false) private String actorRole;
    @Column(name="payload", nullable=false) private String payload;
    @Column(name="created_at", nullable=false) private Instant createdAt;

    protected RevealAuditLog() {}

    public RevealAuditLog(UUID id, UUID requestId, UUID caseId, String eventType,
            String actorSubject, String actorRole, String payload, Instant createdAt) {
        this.id=id;this.requestId=requestId;this.caseId=caseId;this.eventType=eventType;
        this.actorSubject=actorSubject;this.actorRole=actorRole;this.payload=payload;this.createdAt=createdAt;
    }

    public UUID getId(){return id;}
    public UUID getRequestId(){return requestId;}
    public UUID getCaseId(){return caseId;}
    public String getEventType(){return eventType;}
    public String getActorSubject(){return actorSubject;}
    public String getActorRole(){return actorRole;}
    public String getPayload(){return payload;}
    public Instant getCreatedAt(){return createdAt;}
}
