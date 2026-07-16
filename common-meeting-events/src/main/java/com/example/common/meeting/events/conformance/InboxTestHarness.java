package com.example.common.meeting.events.conformance;

/**
 * What a consumer must let {@link ConsumerInboxConformanceKit} do to it — #802 slice 1.
 *
 * <p>The implementation wraps the REAL consumer: its durable eventKey inbox and the real
 * side effect it applies (send the notification, write the projection, …). The kit then
 * counts effects. That counting is the whole point — it is the difference between proving
 * exactly-once EFFECT and merely asserting that two deliveries looked alike.
 *
 * <h2>Two observations, not one</h2>
 * The harness reports both {@link #effectCount} (effects that COMMITTED) and
 * {@link #attemptedEffectCount} (times the consumer reached the point where the effect
 * would be applied, committed or not). One number cannot express the atomicity rule: "no
 * effect committed" is equally true of a consumer that rolled back correctly and of a
 * consumer that was never asked to do anything. Separating attempted from committed is
 * what lets the kit tell those apart — and refuse the second.
 *
 * <p>Implementations may be called from several threads: the kit exercises the concurrent
 * duplicate-collision path, because that is where a check-then-act inbox actually breaks.
 */
public interface InboxTestHarness {

    /**
     * Deliver an event to the consumer, letting it run its normal path: de-duplicate on
     * {@link InboxDelivery#eventKey()}, and if it is new, apply the side effect and record
     * the inbox entry — <b>in one transaction, under an atomic claim</b>.
     *
     * <p>Must be safe to call concurrently for the SAME delivery from several threads.
     * Exactly one of those calls may return {@code true}. A consumer that reads "have I
     * seen this key?" and then writes is not enough: two workers pass the read before
     * either writes, and both send the notification. The real defence is the store's own
     * atomicity — {@code INSERT … ON CONFLICT DO NOTHING} on a UNIQUE {@code event_key},
     * or an equivalent atomic claim.
     *
     * @return {@code true} if THIS call applied the side effect, {@code false} if it was
     *     recognised as a duplicate and suppressed
     */
    boolean deliver(InboxDelivery delivery);

    /**
     * Deliver, but make the consumer's own transaction fail AFTER it reaches the point
     * where the side effect would be applied.
     *
     * <p>This is what separates a real inbox from a fake one. A consumer that applies its
     * effect OUTSIDE the transaction that records the inbox entry leaves the effect behind
     * when the transaction dies — and then a redelivery applies a second one. Only a
     * consumer whose effect and inbox entry commit together can leave zero committed
     * effects here.
     *
     * <p><b>The returned outcome is evidence, and the kit checks it.</b> An implementation
     * that quietly does nothing would make every atomicity rule pass vacuously — the exact
     * "an empty result keeps the test green" defect that {@link DuplicateEventKeyException}
     * closed on the producer side. So the outcome must report that the effect point was
     * genuinely reached and the transaction genuinely failed, and the kit corroborates that
     * claim against {@link #attemptedEffectCount}, which the harness cannot satisfy without
     * actually running the path. A harness that cannot inject a failure must throw
     * {@link UnsupportedOperationException} rather than return a no-op outcome.
     */
    FailureInjection deliverWithFailureAfterEffect(InboxDelivery delivery);

    /**
     * How many times the side effect has actually COMMITTED for this key.
     *
     * <p>Counts real effects (rows written, notifications sent), never inbox rows — an
     * inbox row is the consumer's own bookkeeping and would happily agree with itself.
     */
    int effectCount(String eventKey);

    /**
     * How many times the consumer REACHED the point of applying the effect for this key,
     * whether or not it committed.
     *
     * <p>The corroborating observation for {@link #deliverWithFailureAfterEffect}: it is
     * the difference between "the transaction rolled back" and "nothing ever happened".
     */
    int attemptedEffectCount(String eventKey);

    /** Total COMMITTED side effects across every key — catches effects attributed to the wrong key. */
    int totalEffectCount();

    /** Drop all state, so each conformance test starts from an empty consumer. */
    void reset();

    /**
     * Evidence that a fault injection actually ran.
     *
     * @param effectPointReached the consumer got as far as applying the effect
     * @param transactionFailed  and its transaction then failed rather than committing
     */
    record FailureInjection(boolean effectPointReached, boolean transactionFailed) {

        /** The injection ran as asked. */
        public static FailureInjection injected() {
            return new FailureInjection(true, true);
        }
    }
}
