package com.example.common.meeting.events.conformance;

/**
 * What a consumer must let {@link ConsumerInboxConformanceKit} do to it — #802 slice 1.
 *
 * <p>The implementation wraps the REAL consumer: its durable eventKey inbox and the real
 * side effect it applies (send the notification, write the projection, …). The kit then
 * counts effects. That counting is the whole point — it is the difference between proving
 * exactly-once EFFECT and merely asserting that two deliveries looked alike.
 *
 * <p>Implementations are used from a single test thread and may be stateful.
 */
public interface InboxTestHarness {

    /**
     * Deliver an event to the consumer, letting it run its normal path: de-duplicate on
     * {@link InboxDelivery#eventKey()}, and if it is new, apply the side effect and record
     * the inbox entry — <b>in one transaction</b>.
     *
     * @return {@code true} if THIS delivery applied the side effect, {@code false} if the
     *     consumer recognised a duplicate and suppressed it
     */
    boolean deliver(InboxDelivery delivery);

    /**
     * Deliver, but make the consumer's own transaction fail after the point where the side
     * effect would be applied.
     *
     * <p>This is what separates a real inbox from a fake one. A consumer that applies its
     * effect OUTSIDE the transaction that records the inbox entry leaves the effect behind
     * when the transaction dies — and then a redelivery applies a second one. Only a
     * consumer whose effect and inbox entry commit together can leave zero effects here.
     *
     * <p>Implementations must let the failure surface however their store surfaces it; the
     * kit does not assert on the exception type, only on the effects that remain.
     */
    void deliverWithFailureAfterEffect(InboxDelivery delivery);

    /**
     * How many times the side effect has ACTUALLY been applied for this key.
     *
     * <p>Counts real effects (rows written, notifications sent), never inbox rows — an
     * inbox row is the consumer's own bookkeeping and would happily agree with itself.
     */
    int effectCount(String eventKey);

    /** Total side effects applied across every key — catches effects attributed to the wrong key. */
    int totalEffectCount();

    /** Drop all state, so each conformance test starts from an empty consumer. */
    void reset();
}
