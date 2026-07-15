package com.example.common.meeting.events.conformance;

import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventV1Serializer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A minimal correct outbox, used to prove {@link OutboxConformanceKit} itself runs and
 * that its rules are mutually satisfiable.
 *
 * <p>It is NOT a substitute for a producer running the kit against its real store: the
 * interesting failures (a missing UNIQUE index, a poller claiming uncommitted rows, a
 * JSONB round-trip reordering keys) are exactly the ones an in-memory map cannot have.
 * Its job is to keep the kit honest — a rule that cannot be satisfied here is a rule
 * with a bug in it.
 */
final class InMemoryOutboxHarness implements OutboxTestHarness {

    private static final int MAX_ATTEMPTS = 3;

    /** Insertion-ordered: the poller contract is oldest-first. */
    private final Map<String, StoredOutboxEvent> rows = new LinkedHashMap<>();

    @Override
    public void appendInTransaction(final List<MeetingEventEnvelope> events, final boolean commit) {
        // Staged first, applied only on commit — the whole point of the rollback rule.
        final Map<String, StoredOutboxEvent> staged = new LinkedHashMap<>();
        for (MeetingEventEnvelope event : events) {
            final String key = event.eventKey();
            if (rows.containsKey(key) || staged.containsKey(key)) {
                // Stands in for the UNIQUE index; the transaction aborts, staging is dropped.
                throw new IllegalStateException("duplicate event_key: " + key);
            }
            staged.put(key, new StoredOutboxEvent(
                    key,
                    event.eventType().wireValue(),
                    MeetingEventV1Serializer.toJson(event),
                    false,
                    0,
                    false));
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
            return; // idempotent
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
                row.eventKey(),
                row.eventType(),
                row.payloadJson(),
                row.published(),
                attempts,
                attempts >= MAX_ATTEMPTS));
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
