package com.example.meeting.events;

/**
 * Transport seam for meeting analysis domain events — Faz 24 (platform-ai#244
 * BE-1d). The {@code MeetingEventOutboxPoller} calls this for each COMMITTED
 * outbox row it claims.
 *
 * <p><b>Contract.</b> {@link #publish} returns normally iff the event was handed
 * to the transport (accepted/acked); it throws to signal a delivery failure so
 * the poller retries (and eventually dead-letters). Implementations MUST be safe
 * to call again for the same {@link MeetingEventMessage#eventKey()} — the outbox
 * is at-least-once, so exactly-once effect is the consumer's job (de-dup on
 * {@code eventKey}).
 *
 * <h2>This slice's boundary (documented, not hidden)</h2>
 * BE-1d delivers the full outbox → poller → publisher pipeline plus the event
 * schema and idempotency. The default implementation is
 * {@link LoggingMeetingEventPublisher} — a real, observable (log-line) delivery
 * that marks the seam. Wiring a concrete transport (a Redis {@code meeting:events}
 * stream, or an internal HTTP intent to notification-orchestrator) and the
 * downstream {@code #412} consumer are a SEPARATE slice: this interface is the
 * single injection point they plug into, with no change to the poller or the
 * outbox.
 */
public interface MeetingEventPublisher {

    /**
     * Deliver one event to the transport.
     *
     * @param event the committed, thin event message
     * @throws RuntimeException if delivery fails (poller will retry / dead-letter)
     */
    void publish(MeetingEventMessage event);
}
