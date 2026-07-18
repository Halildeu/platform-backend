package com.example.transcript.repository;

import com.example.transcript.model.TranscriptMeetingEventInbox;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TranscriptMeetingEventInboxRepository
        extends JpaRepository<TranscriptMeetingEventInbox, UUID> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO {h-schema}transcript_meeting_event_inbox
            (id, event_key, event_type, payload_sha256, tenant_id, org_id,
             meeting_id, session_id, source_session_id, received_at)
        VALUES (:id, :eventKey, :eventType, :payloadSha256, :tenantId, :tenantId,
                :meetingId, :sessionId, :sourceSessionId, :receivedAt)
        ON CONFLICT (event_key) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("eventKey") String eventKey,
            @Param("eventType") String eventType,
            @Param("payloadSha256") String payloadSha256,
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId,
            @Param("sourceSessionId") String sourceSessionId,
            @Param("receivedAt") Instant receivedAt);

    Optional<TranscriptMeetingEventInbox> findByEventKey(String eventKey);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}transcript_meeting_event_inbox
        SET processed_at = :processedAt
        WHERE event_key = :eventKey AND processed_at IS NULL
        """, nativeQuery = true)
    int markProcessed(
            @Param("eventKey") String eventKey,
            @Param("processedAt") Instant processedAt);
}
