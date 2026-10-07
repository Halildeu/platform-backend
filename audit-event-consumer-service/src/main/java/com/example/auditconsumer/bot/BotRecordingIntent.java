package com.example.auditconsumer.bot;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bot_recording_intent")
class BotRecordingIntent {
    @Id UUID id;
    @Column(name = "company_id", nullable = false, updatable = false) long companyId;
    @Column(name = "request_key", nullable = false, updatable = false) UUID requestKey;
    @Column(name = "owner_issuer", nullable = false, updatable = false, length = 512) String ownerIssuer;
    @Column(name = "owner_subject", nullable = false, updatable = false, length = 255) String ownerSubject;
    @Column(name = "grant_json", nullable = false, updatable = false, columnDefinition = "text") String grantJson;
    @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    @Column(name = "expires_at", nullable = false, updatable = false) Instant expiresAt;
    @Column(nullable = false, length = 16) String state;
    @Column(nullable = false) long revision;
    @Column(name = "binding_json", columnDefinition = "text") String bindingJson;
    @Column(name = "revoked_at") Instant revokedAt;
    protected BotRecordingIntent() {}
}
