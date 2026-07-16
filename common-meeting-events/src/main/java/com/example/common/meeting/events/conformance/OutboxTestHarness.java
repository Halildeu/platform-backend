package com.example.common.meeting.events.conformance;

import com.example.common.meeting.events.MeetingEventEnvelope;

import java.util.List;
import java.util.Optional;

/**
 * What a producer must let the conformance kit do to its outbox — #802 slice 1.
 *
 * <p>The kit tests BEHAVIOUR, not schema, so this SPI is the whole coupling surface: a
 * producer implements these against its own store (Postgres + its own poller, or a
 * fake) and inherits every rule in {@link OutboxConformanceKit} for free. Nothing here
 * mentions JPA, Spring, SQL or a broker — that is the point.
 *
 * <p>Implementations are used from a single test thread and may be stateful.
 */
public interface OutboxTestHarness {

    /**
     * Append events inside ONE transaction with the (simulated) business write, then
     * commit or roll back.
     *
     * <p>The commit flag is what makes the crash-window rules testable: the outbox
     * pattern's entire premise is that the business row and its events share a
     * transaction, so a rollback must leave neither.
     *
     * <p><b>Duplicate keys must be reported as {@link DuplicateEventKeyException}.</b>
     * The implementation translates its store's native rejection (Postgres SQLState
     * 23505, Spring {@code DuplicateKeyException}, …) into that type. This is a contract,
     * not a convenience: the kit has to distinguish "the store enforced idempotency" from
     * "the second call failed for some unrelated reason", and only the harness knows its
     * store's dialect well enough to tell them apart.
     *
     * @param events  the envelopes to append
     * @param commit  {@code true} to commit, {@code false} to roll back
     * @throws DuplicateEventKeyException if any event key already exists in the store
     */
    void appendInTransaction(List<MeetingEventEnvelope> events, boolean commit);

    /** The row for a key, if the store holds one. */
    Optional<StoredOutboxEvent> findByEventKey(String eventKey);

    /**
     * What a poller would claim right now: unpublished, not dead-lettered, oldest first.
     * Must never return a row from an uncommitted transaction.
     */
    List<StoredOutboxEvent> pollPublishable(int limit);

    /** Record a confirmed delivery. Must be idempotent. */
    void markPublished(String eventKey);

    /** Record a failed delivery attempt; dead-letters the row once attempts are exhausted. */
    void markAttemptFailed(String eventKey, String reason);

    /** The attempt ceiling after which {@link #markAttemptFailed} dead-letters. Must be &gt;= 1. */
    int maxDeliveryAttempts();

    /** Drop all state, so each conformance test starts from an empty store. */
    void reset();
}
