package com.example.meeting.model;

/**
 * The meeting analysis domain events emitted via the transactional outbox —
 * Faz 24 (platform-ai#244 BE-1d), the two persistence-blocked events of
 * platform-backend#412.
 *
 * <p>The enum constant is the in-code, type-safe handle; {@link #wireValue()} is
 * the dotted string persisted in {@code meeting_event_outbox.event_type} and put
 * on the wire. They are stored as the wire value (not the enum name) so the DB
 * CHECK and any downstream consumer see the canonical event name.
 */
public enum MeetingEventType {

    /** The analysis result is durably stored and its summary is ready to read. */
    SUMMARY_READY("meeting.summary.ready"),

    /** An AI-extracted action item has a real, non-blank assignee. */
    ACTION_ASSIGNED("meeting.action.assigned"),

    /** A recording session acquired its first immutable end timestamp. */
    RECORDING_FINISHED("meeting.recording.finished");

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
        for (MeetingEventType type : values()) {
            if (type.wireValue.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown meeting event type: " + value);
    }
}
