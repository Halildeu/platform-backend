package com.example.ethics.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="ethics_intake_idempotency", uniqueConstraints=@UniqueConstraint(name="uq_ethics_intake_idempotency", columnNames={"org_id","channel","idempotency_key"}))
public class IntakeIdempotency {
    @Id private UUID id;
    @Column(name="org_id", nullable=false) private UUID orgId;
    @Column(nullable=false) private String channel;
    @Column(name="idempotency_key", nullable=false, length=200) private String idempotencyKey;
    @Column(name="request_hash", nullable=false, length=64) private String requestHash;
    @Column(name="receipt_id", nullable=false) private UUID receiptId;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected IntakeIdempotency() {}
    public IntakeIdempotency(UUID id, UUID orgId, String channel, String idempotencyKey, String requestHash, UUID receiptId, Instant now){this.id=id;this.orgId=orgId;this.channel=channel;this.idempotencyKey=idempotencyKey;this.requestHash=requestHash;this.receiptId=receiptId;this.createdAt=now;}
    public String getRequestHash(){return requestHash;} public UUID getReceiptId(){return receiptId;}
}
