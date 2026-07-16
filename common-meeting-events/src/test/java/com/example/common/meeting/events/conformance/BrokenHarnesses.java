package com.example.common.meeting.events.conformance;

import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventV1Serializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /**
     * A consumer with no inbox at all: it just does the work every time it is handed an
     * event. Under at-least-once delivery this sends the notification twice.
     */
    static final class NoDedupInbox implements InboxTestHarness {

        private final Map<String, Integer> effects = new HashMap<>();

        @Override
        public boolean deliver(final InboxDelivery delivery) {
            effects.merge(delivery.eventKey(), 1, Integer::sum);
            return true;
        }

        @Override
        public void deliverWithFailureAfterEffect(final InboxDelivery delivery) {
            // no-op
        }

        @Override
        public int effectCount(final String eventKey) {
            return effects.getOrDefault(eventKey, 0);
        }

        @Override
        public int totalEffectCount() {
            return effects.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public void reset() {
            effects.clear();
        }
    }

    /**
     * A consumer that applies its side effect BEFORE, and outside, the transaction that
     * records the inbox entry. It looks correct on the happy path and de-duplicates fine —
     * but a transaction failure leaves the effect behind, and the redelivery adds a second.
     * This is the single most likely real-world inbox bug.
     */
    static final class EffectOutsideTransactionInbox implements InboxTestHarness {

        private final Set<String> inbox = new HashSet<>();
        private final Map<String, Integer> effects = new HashMap<>();

        @Override
        public boolean deliver(final InboxDelivery delivery) {
            if (inbox.contains(delivery.eventKey())) {
                return false;
            }
            effects.merge(delivery.eventKey(), 1, Integer::sum);
            inbox.add(delivery.eventKey());
            return true;
        }

        @Override
        public void deliverWithFailureAfterEffect(final InboxDelivery delivery) {
            if (inbox.contains(delivery.eventKey())) {
                return;
            }
            // The effect is applied outside the transaction, so the rollback below cannot
            // take it back: the inbox entry is lost but the notification already went out.
            effects.merge(delivery.eventKey(), 1, Integer::sum);
            // ... transaction fails here; inbox entry never committed.
        }

        @Override
        public int effectCount(final String eventKey) {
            return effects.getOrDefault(eventKey, 0);
        }

        @Override
        public int totalEffectCount() {
            return effects.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public void reset() {
            inbox.clear();
            effects.clear();
        }
    }

    /**
     * A consumer whose de-duplication is a one-slot "last event seen" cache rather than a
     * durable inbox. Correct for an immediate retry, wrong the moment any other event is
     * interleaved — which is normal traffic.
     */
    static final class LastSeenOnlyInbox implements InboxTestHarness {

        private final Map<String, Integer> effects = new HashMap<>();
        private String lastSeen;

        @Override
        public boolean deliver(final InboxDelivery delivery) {
            if (delivery.eventKey().equals(lastSeen)) {
                return false;
            }
            effects.merge(delivery.eventKey(), 1, Integer::sum);
            lastSeen = delivery.eventKey();
            return true;
        }

        @Override
        public void deliverWithFailureAfterEffect(final InboxDelivery delivery) {
            // no-op
        }

        @Override
        public int effectCount(final String eventKey) {
            return effects.getOrDefault(eventKey, 0);
        }

        @Override
        public int totalEffectCount() {
            return effects.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public void reset() {
            effects.clear();
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
