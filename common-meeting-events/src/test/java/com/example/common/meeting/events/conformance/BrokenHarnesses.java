package com.example.common.meeting.events.conformance;

import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventV1Serializer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deliberately non-conformant implementations, each embodying ONE realistic mistake.
 *
 * <p>They exist so {@link ConformanceKitTeethTest} can prove the kits reject them. A kit
 * that only ever runs against a correct reference proves nothing about what it would
 * catch — that is precisely how the original "exactly-once EFFECT" claim in
 * {@link OutboxConformanceKit} became a false positive.
 *
 * <p>Every mistake here is one a real implementation could plausibly make, not a strawman.
 */
final class BrokenHarnesses {

    private BrokenHarnesses() {
    }

    // ────────────────────────── consumer-side ──────────────────────────

    /** Shared counting for the consumer-side broken harnesses. */
    abstract static class CountingInbox implements InboxTestHarness {

        final Map<String, AtomicInteger> committed = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> attempted = new ConcurrentHashMap<>();

        static void count(final Map<String, AtomicInteger> counts, final String key) {
            counts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }

        @Override
        public FailureInjection deliverWithFailureAfterEffect(final InboxDelivery delivery) {
            count(attempted, delivery.eventKey());
            return FailureInjection.injected();
        }

        @Override
        public int effectCount(final String eventKey) {
            final AtomicInteger n = committed.get(eventKey);
            return n == null ? 0 : n.get();
        }

        @Override
        public int attemptedEffectCount(final String eventKey) {
            final AtomicInteger n = attempted.get(eventKey);
            return n == null ? 0 : n.get();
        }

        @Override
        public int totalEffectCount() {
            return committed.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        @Override
        public void reset() {
            committed.clear();
            attempted.clear();
        }
    }

    /**
     * A consumer with no inbox at all: it just does the work every time it is handed an
     * event. Under at-least-once delivery this sends the notification twice.
     */
    static final class NoDedupInbox extends CountingInbox {

        @Override
        public boolean deliver(final InboxDelivery delivery) {
            count(attempted, delivery.eventKey());
            count(committed, delivery.eventKey());
            return true;
        }
    }

    /**
     * A consumer whose {@code deliverWithFailureAfterEffect} quietly does nothing — the
     * defect the review named. Its de-duplication is fine, so it passes every redelivery
     * rule; but its atomicity rules would pass VACUOUSLY, satisfying "no effect committed"
     * by never attempting one. The kit must refuse it on the evidence, not the outcome.
     */
    static final class NoOpFailureInjectionInbox extends CountingInbox {

        private final Map<String, Boolean> inbox = new ConcurrentHashMap<>();

        @Override
        public boolean deliver(final InboxDelivery delivery) {
            final boolean[] applied = {false};
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
            // Claims the injection ran while doing nothing at all. The outcome alone cannot
            // catch this — only the independent attempted-effect observation can.
            return FailureInjection.injected();
        }

        @Override
        public void reset() {
            super.reset();
            inbox.clear();
        }
    }

    /**
     * A consumer that reads "have I seen this key?" and then writes, with no atomic claim —
     * a plain {@code SELECT} followed by an {@code INSERT}, or an inbox table with no
     * UNIQUE index on {@code event_key}.
     *
     * <p>Correct under every sequential test. Under a real collision both workers pass the
     * read before either writes, and the notification goes out twice. The barrier makes
     * that window deterministic rather than hoping the scheduler produces it — the bug is
     * real either way, the barrier just stops the test from being flaky about it.
     */
    static final class CheckThenActInbox extends CountingInbox {

        private final Map<String, Boolean> inbox = new ConcurrentHashMap<>();
        private final CyclicBarrier insideTheWindow;

        CheckThenActInbox(final int racers) {
            this.insideTheWindow = new CyclicBarrier(racers);
        }

        @Override
        public boolean deliver(final InboxDelivery delivery) {
            final boolean seen = inbox.containsKey(delivery.eventKey());
            try {
                // Hold every racer between the check and the act.
                insideTheWindow.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (BrokenBarrierException | TimeoutException e) {
                // Fewer racers than expected; the window simply does not open.
            }
            if (seen) {
                return false;
            }
            count(attempted, delivery.eventKey());
            count(committed, delivery.eventKey());
            inbox.put(delivery.eventKey(), Boolean.TRUE);
            return true;
        }
    }

    /**
     * A consumer that applies its side effect BEFORE, and outside, the transaction that
     * records the inbox entry. It looks correct on the happy path and de-duplicates fine —
     * but a transaction failure leaves the effect behind, and the redelivery adds a second.
     * This is the single most likely real-world inbox bug.
     */
    static final class EffectOutsideTransactionInbox extends CountingInbox {

        private final Map<String, Boolean> inbox = new ConcurrentHashMap<>();

        @Override
        public boolean deliver(final InboxDelivery delivery) {
            if (inbox.containsKey(delivery.eventKey())) {
                return false;
            }
            count(attempted, delivery.eventKey());
            count(committed, delivery.eventKey());
            inbox.put(delivery.eventKey(), Boolean.TRUE);
            return true;
        }

        @Override
        public FailureInjection deliverWithFailureAfterEffect(final InboxDelivery delivery) {
            if (inbox.containsKey(delivery.eventKey())) {
                return new FailureInjection(false, false);
            }
            // The effect is applied outside the transaction, so the rollback cannot take it
            // back: the inbox entry is never committed but the notification already went out.
            count(attempted, delivery.eventKey());
            count(committed, delivery.eventKey());
            return FailureInjection.injected();
        }

        @Override
        public void reset() {
            super.reset();
            inbox.clear();
        }
    }

    /**
     * A consumer whose de-duplication is a one-slot "last event seen" cache rather than a
     * durable inbox. Correct for an immediate retry, wrong the moment any other event is
     * interleaved — which is normal traffic.
     */
    static final class LastSeenOnlyInbox extends CountingInbox {

        private String lastSeen;

        @Override
        public synchronized boolean deliver(final InboxDelivery delivery) {
            if (delivery.eventKey().equals(lastSeen)) {
                return false;
            }
            count(attempted, delivery.eventKey());
            count(committed, delivery.eventKey());
            lastSeen = delivery.eventKey();
            return true;
        }

        @Override
        public void reset() {
            super.reset();
            lastSeen = null;
        }
    }

    // ────────────────────────── producer-side ──────────────────────────

    /**
     * An outbox with no UNIQUE index: a duplicate append is simply accepted, so a retried
     * ingestion double-emits. Nothing throws, so an assertion that merely expected "an
     * exception" would at least notice — but one that expected nothing specific about the
     * store's enforcement would not.
     */
    static final class NoUniqueIndexOutbox extends DelegatingOutboxHarness {

        @Override
        public void appendInTransaction(final List<MeetingEventEnvelope> events, final boolean commit) {
            appendWithoutDuplicateCheck(events, commit);
        }
    }

    /**
     * An outbox that DOES reject duplicates, but whose second call fails for an unrelated
     * reason — a closed connection, a mapping bug — rather than a duplicate-key rejection.
     * This is the case Halil's review caught: an assertion that only checks "something was
     * thrown" stays green here while the UNIQUE constraint may not exist at all.
     */
    static final class WrongExceptionOutbox extends DelegatingOutboxHarness {

        @Override
        public void appendInTransaction(final List<MeetingEventEnvelope> events, final boolean commit) {
            for (MeetingEventEnvelope event : events) {
                if (contains(event.eventKey())) {
                    throw new IllegalStateException("connection closed");
                }
            }
            appendWithoutDuplicateCheck(events, commit);
        }
    }

    /** Shared storage for the producer-side broken harnesses. */
    abstract static class DelegatingOutboxHarness implements OutboxTestHarness {

        private static final int MAX_ATTEMPTS = 3;

        private final Map<String, StoredOutboxEvent> rows = new LinkedHashMap<>();

        boolean contains(final String eventKey) {
            return rows.containsKey(eventKey);
        }

        void appendWithoutDuplicateCheck(final List<MeetingEventEnvelope> events, final boolean commit) {
            final Map<String, StoredOutboxEvent> staged = new LinkedHashMap<>();
            for (MeetingEventEnvelope event : events) {
                staged.put(event.eventKey(), new StoredOutboxEvent(
                        event.eventKey(),
                        event.eventType().wireValue(),
                        MeetingEventV1Serializer.toJson(event),
                        false, 0, false));
            }
            if (commit) {
                rows.putAll(staged);
            }
        }

        @Override
        public Optional<StoredOutboxEvent> findByEventKey(final String eventKey) {
            return Optional.ofNullable(rows.get(eventKey));
        }

        @Override
        public List<StoredOutboxEvent> pollPublishable(final int limit) {
            final List<StoredOutboxEvent> claimed = new ArrayList<>();
            for (StoredOutboxEvent row : rows.values()) {
                if (claimed.size() == limit) {
                    break;
                }
                if (!row.published() && !row.deadLettered()) {
                    claimed.add(row);
                }
            }
            return claimed;
        }

        @Override
        public void markPublished(final String eventKey) {
            final StoredOutboxEvent row = rows.get(eventKey);
            if (row == null || row.published()) {
                return;
            }
            rows.put(eventKey, new StoredOutboxEvent(
                    row.eventKey(), row.eventType(), row.payloadJson(), true, row.attempts(), row.deadLettered()));
        }

        @Override
        public void markAttemptFailed(final String eventKey, final String reason) {
            final StoredOutboxEvent row = rows.get(eventKey);
            if (row == null) {
                return;
            }
            final int attempts = row.attempts() + 1;
            rows.put(eventKey, new StoredOutboxEvent(
                    row.eventKey(), row.eventType(), row.payloadJson(),
                    row.published(), attempts, attempts >= MAX_ATTEMPTS));
        }

        @Override
        public int maxDeliveryAttempts() {
            return MAX_ATTEMPTS;
        }

        @Override
        public void reset() {
            rows.clear();
        }
    }
}
