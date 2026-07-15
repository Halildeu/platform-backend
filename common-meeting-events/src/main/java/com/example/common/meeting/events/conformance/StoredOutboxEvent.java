package com.example.common.meeting.events.conformance;

/**
 * One outbox row as the conformance kit sees it — #802 slice 1.
 *
 * <p>Deliberately a value type with no storage in it. Each producer keeps its own
 * table, migration and entity; this is only the projection the shared behaviour rules
 * are expressed against, so the kit can assert semantics without ever knowing whether
 * the row came from Postgres, an in-memory fake or something else.
 *
 * @param eventKey      the deterministic idempotency key
 * @param eventType     the canonical dotted wire name
 * @param payloadJson   the rendered {@code meeting.event.v1} bytes
 * @param published     whether delivery has been confirmed and recorded
 * @param attempts      how many delivery attempts have been made
 * @param deadLettered  whether the row was parked after exhausting its attempts
 */
public record StoredOutboxEvent(
        String eventKey,
        String eventType,
        String payloadJson,
        boolean published,
        int attempts,
        boolean deadLettered) {
}
