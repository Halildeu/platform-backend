package com.example.common.meeting.events.conformance;

/**
 * A store rejected an append because the event key already exists — #802 slice 1.
 *
 * <p>This is the ONE outcome that proves idempotency is enforced by the store rather
 * than by an application check: two racing writers both see "no existing row" before
 * either commits, so only a UNIQUE constraint can decide. An {@link OutboxTestHarness}
 * therefore translates its store's native duplicate-key rejection into this type —
 * Postgres {@code SQLState 23505}, an H2 {@code JdbcSQLIntegrityConstraintViolationException},
 * a Spring {@code DuplicateKeyException}, whatever the store actually raises.
 *
 * <p><b>Why the translation is the harness's job and not the kit's:</b> the kit must be
 * able to tell "the store rejected a duplicate" apart from "the second call happened to
 * blow up for an unrelated reason" — a NullPointerException, a closed connection, a
 * serialization failure. Asserting merely that *something* was thrown would let all of
 * those keep the test green while idempotency was silently broken. Only the harness
 * knows its store's dialect, so only the harness can make that distinction faithfully.
 */
public class DuplicateEventKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String eventKey;

    /**
     * @param eventKey the key that already existed
     * @param cause    the store's native rejection, kept so a failure is diagnosable
     */
    public DuplicateEventKeyException(final String eventKey, final Throwable cause) {
        super("Event key already exists in the outbox: " + eventKey, cause);
        this.eventKey = eventKey;
    }

    public DuplicateEventKeyException(final String eventKey) {
        this(eventKey, null);
    }

    /** The duplicate key, so a caller can report which fact collided. */
    public String eventKey() {
        return eventKey;
    }
}
