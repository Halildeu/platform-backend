package com.example.ethics.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="mailbox_sessions")
public class MailboxSession {
    @Id @Column(name="token_hash", length=64) private String tokenHash;
    @Column(name="case_id", nullable=false) private UUID caseId;
    @Column(nullable=false, length=80) private String channel;
    @Column(name="expires_at", nullable=false) private Instant expiresAt;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    protected MailboxSession() {}
    public MailboxSession(String tokenHash, UUID caseId, String channel, Instant expiresAt, Instant createdAt){this.tokenHash=tokenHash;this.caseId=caseId;this.channel=channel;this.expiresAt=expiresAt;this.createdAt=createdAt;}
    public UUID getCaseId(){return caseId;} public String getChannel(){return channel;} public Instant getExpiresAt(){return expiresAt;}
}
