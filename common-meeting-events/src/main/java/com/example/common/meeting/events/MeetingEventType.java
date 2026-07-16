package com.example.common.meeting.events;

import java.util.Optional;

/**
 * The public meeting event types — #802 slice 1.
 *
 * <p>The enum constant is the type-safe in-code handle; {@link #wireValue()} is the
 * canonical dotted name that goes on the wire and into each producer's own outbox
 * {@code event_type} column. Producers persist the wire value (not the enum name) so
 * the store and every downstream consumer see the same canonical string.
 *
 * <p><b>Only shipped types live here.</b> {@code meeting.consent.revoked} and
 * {@code meeting.transcript.ready} are named in the #802 owner decision as later
 * slices (audio-gateway / transcript-service, each with its own DB-local outbox), but
 * their payload contracts are not fixed yet. Adding their names here before a producer
 * emits them would publish a contract nobody has agreed to; until then they are, by
 * design, {@link #fromWire unknown} and rejected fail-closed.
 */
public enum MeetingEventType {

    /** The analysis result is durably stored and its summary is ready to read. */
    SUMMARY_READY("meeting.summary.ready"),

    /** An AI-extracted action item has a real, non-blank assignee. */
    ACTION_ASSIGNED("meeting.action.assigned");

    private final String wireValue;

    MeetingEventType(final String wireValue) {
        this.wireValue = wireValue;
    }

    /** The canonical dotted event name persisted and published (e.g. {@code meeting.summary.ready}). */
    public String wireValue() {
        return wireValue;
    }

    /** Resolve from a persisted/wire value; throws if unknown (fail-closed). */
    public static MeetingEventType fromWire(final String value) {
        return find(value).orElseThrow(() ->
                new MeetingEventValidationException("Unknown meeting event type: " + value));
    }

    /**
     * Non-throwing lookup, for a consumer that must tell "type I do not handle" apart
     * from "malformed event" without exception control flow.
     */
    public static Optional<MeetingEventType> find(final String value) {
        for (MeetingEventType type : values()) {
            if (type.wireValue.equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
