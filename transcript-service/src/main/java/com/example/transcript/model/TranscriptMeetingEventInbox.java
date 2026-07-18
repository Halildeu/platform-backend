package com.example.transcript.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Metadata-only idempotency ledger for consumed meeting events. */
@Entity
@Table(name = "transcript_meeting_event_inbox")
public class TranscriptMeetingEventInbox {

    @Id
    private UUID id;

    @Column(name = "event_key", nullable = false, length = 240, updatable = false)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    @Column(name = "payload_sha256", nullable = false, length = 64, updatable = false)
    private String payloadSha256;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "org_id", updatable = false)
    private UUID orgId;

    @Column(name = "meeting_id", nullable = false, updatable = false)
    private UUID meetingId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "source_session_id", nullable = false, length = 128, updatable = false)
    private String sourceSessionId;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    public UUID getId() { return id; }
    public String getEventKey() { return eventKey; }
    public String getEventType() { return eventType; }
    public String getPayloadSha256() { return payloadSha256; }
    public UUID getTenantId() { return tenantId; }
    public UUID getOrgId() { return orgId; }
    public UUID getMeetingId() { return meetingId; }
    public UUID getSessionId() { return sessionId; }
    public String getSourceSessionId() { return sourceSessionId; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
}
