package com.example.meeting.events;

import com.example.meeting.model.MeetingEventOutbox;

import java.util.UUID;

/**
 * The immutable message a {@link MeetingEventPublisher} delivers for one committed
 * outbox row — Faz 24 (platform-ai#244 BE-1d).
 *
 * <p>{@link #eventKey()} is the stable idempotency handle: a consumer that de-dups
 * on it applies each side-effect exactly once even if the same event is delivered
 * more than once (the outbox is at-least-once). {@link #payloadJson()} is the
 * thin, redaction-aware event body (identifiers + minimal metadata — never the
 * summary/transcript text).
 */
public record MeetingEventMessage(
        String eventKey,
        String eventType,
        UUID aggregateId,
        UUID meetingId,
        UUID tenantId,
        UUID orgId,
        String payloadJson) {

    public static MeetingEventMessage from(final MeetingEventOutbox row) {
        return new MeetingEventMessage(
                row.getEventKey(),
                row.getEventType(),
                row.getAggregateId(),
                row.getMeetingId(),
                row.getTenantId(),
                row.getOrgId(),
                row.getPayload());
    }
}
