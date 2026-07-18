package com.example.ethics.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="reporter_access_grants")
public class ReporterAccessGrant {
    @Id @Column(name="receipt_id") private UUID receiptId;
    @Column(name="case_id", nullable=false, unique=true) private UUID caseId;
    @Column(name="secret_hash", nullable=false, length=512) private String secretHash;
    @Column(name="failed_attempts", nullable=false) private int failedAttempts;
    @Column(name="locked_until") private Instant lockedUntil;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected ReporterAccessGrant() {}
    public ReporterAccessGrant(UUID receiptId, UUID caseId, String secretHash, Instant now){this.receiptId=receiptId;this.caseId=caseId;this.secretHash=secretHash;this.createdAt=now;}
    public UUID getReceiptId(){return receiptId;} public UUID getCaseId(){return caseId;} public String getSecretHash(){return secretHash;}
    public int getFailedAttempts(){return failedAttempts;} public Instant getLockedUntil(){return lockedUntil;}
    public void failed(Instant now){failedAttempts++; if(failedAttempts>=5) lockedUntil=now.plusSeconds(900);}
    public void verified(){failedAttempts=0;lockedUntil=null;}
}
