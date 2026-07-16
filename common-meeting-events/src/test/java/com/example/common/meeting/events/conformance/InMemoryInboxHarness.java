package com.example.common.meeting.events.conformance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal correct consumer inbox, used to prove {@link ConsumerInboxConformanceKit}
 * itself runs and that its rules are mutually satisfiable.
 *
 * <p>It models the two structural properties the kit tests:
 *
 * <ul>
 *   <li><b>An atomic claim per event key.</b> {@link ConcurrentHashMap#computeIfAbsent} is
 *       atomic for a key, so exactly one of several racing callers runs the mapping
 *       function. It stands in for {@code INSERT … ON CONFLICT DO NOTHING} on a UNIQUE
 *       {@code event_key}. A {@code contains()} followed by {@code add()} would look
 *       equivalent and lose the race — see {@code BrokenHarnesses.CheckThenActInbox}.</li>
 *   <li><b>Effect and inbox entry commit together.</b> The effect is applied INSIDE the
 *       mapping function, so there is no window where one exists without the other —
 *       what a real consumer buys by sharing one transaction between the two.</li>
 * </ul>
 *
 * <p>It is NOT a substitute for a real consumer running the kit against its real store:
 * the failures that matter most (a missing UNIQUE index, an isolation level that lets two
 * transactions both read "absent", a claim that races across processes) are properties of
 * the deployed database, not of this map. Its job is to keep the kit honest — a rule that
 * cannot be satisfied here is a rule with a bug in it.
 */
final class InMemoryInboxHarness implements InboxTestHarness {

    /** The durable inbox: event keys whose effect has committed. The claim is on this map. */
    private final Map<String, Boolean> inbox = new ConcurrentHashMap<>();

    /** Committed side effects per key — what the kit asserts on. */
    private final Map<String, AtomicInteger> committed = new ConcurrentHashMap<>();

    /** Times the effect point was reached, committed or not — the fault-injection witness. */
    private final Map<String, AtomicInteger> attempted = new ConcurrentHashMap<>();

    @Override
    public boolean deliver(final InboxDelivery delivery) {
        final boolean[] applied = {false};
        // Atomic claim: only one caller's mapping function runs for a given key, so only
        // one caller can apply the effect no matter how many race here.
        inbox.computeIfAbsent(delivery.eventKey(), key -> {
            count(attempted, key);
            count(committed, key);
            applied[0] = true;
            return Boolean.TRUE;
        });
        return applied[0];
    }

    @Override
    public FailureInjection deliverWithFailureAfterEffect(final InboxDelivery delivery) {
        if (inbox.containsKey(delivery.eventKey())) {
            // Already applied; the consumer would suppress before reaching the effect.
            return new FailureInjection(false, false);
        }
        // Reach the effect point for real — this is the witness the kit corroborates. The
        // transaction then dies before commit, and because the effect only ever lands as
        // part of the claim above, there is nothing left behind. A consumer that applied
        // its effect here, outside the claim, would leave an orphan and fail the kit.
        count(attempted, delivery.eventKey());
        return FailureInjection.injected();
    }

    @Override
    public int effectCount(final String eventKey) {
        return value(committed, eventKey);
    }

    @Override
    public int attemptedEffectCount(final String eventKey) {
        return value(attempted, eventKey);
    }

    @Override
    public int totalEffectCount() {
        return committed.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    @Override
    public void reset() {
        inbox.clear();
        committed.clear();
        attempted.clear();
    }

    private static void count(final Map<String, AtomicInteger> counts, final String key) {
        counts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private static int value(final Map<String, AtomicInteger> counts, final String key) {
        final AtomicInteger n = counts.get(key);
        return n == null ? 0 : n.get();
    }
}
