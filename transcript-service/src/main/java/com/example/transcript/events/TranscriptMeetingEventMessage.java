package com.example.transcript.events;

import com.example.transcript.model.TranscriptEventOutbox;
import java.util.UUID;

/**
 * Thin transport message rehydrated from one committed outbox row.
 *
 * <p>The payload is the immutable {@code meeting.event.v1} serializer text. Rehydration must pass
 * it through verbatim; parsing and reserializing would make frozen field order depend on a mapper.
 */
public record TranscriptMeetingEventMessage(
        String eventKey,
        String eventType,
        UUID aggregateId,
        UUID meetingId,
        UUID tenantId,
        UUID orgId,
        String payloadJson) {

    public static TranscriptMeetingEventMessage from(TranscriptEventOutbox row) {
        return new TranscriptMeetingEventMessage(
                row.getEventKey(), row.getEventType(), row.getAggregateId(),
                row.getMeetingId(), row.getTenantId(), row.getOrgId(), row.getPayload());
    }
}
