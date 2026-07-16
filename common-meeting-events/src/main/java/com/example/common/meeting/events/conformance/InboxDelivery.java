package com.example.common.meeting.events.conformance;

import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventV1Serializer;

/**
 * One event as a consumer receives it — #802 slice 1.
 *
 * <p>Deliberately just the three things that actually cross the wire. A consumer does not
 * get an envelope object, a database row or a transport handle: it gets a key to
 * de-duplicate on, a type to dispatch on, and bytes to parse. Modelling the delivery as
 * anything richer would let a consumer conformance test lean on information the real
 * consumer will not have.
 *
 * @param eventKey    the deterministic idempotency key — the ONLY thing de-duplication keys on
 * @param eventType   the canonical dotted wire name
 * @param payloadJson the rendered {@code meeting.event.v1} bytes
 */
public record InboxDelivery(String eventKey, String eventType, String payloadJson) {

    /** The delivery a producer's envelope becomes on the wire. */
    public static InboxDelivery of(final MeetingEventEnvelope envelope) {
        return new InboxDelivery(
                envelope.eventKey(),
                envelope.eventType().wireValue(),
                MeetingEventV1Serializer.toJson(envelope));
    }

    /** The same fact delivered again — byte-identical, as the outbox kit proves it must be. */
    public InboxDelivery redelivery() {
        return new InboxDelivery(eventKey, eventType, payloadJson);
    }
}
