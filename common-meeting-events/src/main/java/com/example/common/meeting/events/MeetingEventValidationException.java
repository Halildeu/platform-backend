package com.example.common.meeting.events;

/**
 * A meeting event violates the shared contract — unknown type, missing required
 * field, or a payload that contradicts its envelope.
 *
 * <p>Unchecked on purpose: every throw site is a programming/contract error at the
 * producer, not a condition a caller can meaningfully recover from mid-build. A
 * consumer that must classify rather than crash uses {@link MeetingEventType#find}
 * and {@link MeetingEventValidator#validationErrors} instead of catching this.
 */
public class MeetingEventValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MeetingEventValidationException(final String message) {
        super(message);
    }
}
