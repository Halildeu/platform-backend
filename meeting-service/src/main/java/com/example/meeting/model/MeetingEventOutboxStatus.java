package com.example.meeting.model;

/**
 * Lifecycle of a {@link MeetingEventOutbox} row — Faz 24 (platform-ai#244 BE-1d).
 *
 * <pre>
 *   PENDING ──claim──▶ CLAIMED ──publish ok──▶ PUBLISHED   (success terminal)
 *      ▲                  │
 *      │                  ├─publish fail, attempts &lt; max─▶ PENDING (retry)
 *      │                  └─publish fail, attempts &gt;= max─▶ DEAD  (failure terminal)
 *      └───stale lease (lease_expires_at &lt;= now) recovered──┘
 * </pre>
 *
 * <p>Persisted as {@code VARCHAR(16)} via {@code @Enumerated(STRING)} — the names
 * match the DB {@code meeting_event_outbox_status_known} CHECK.
 */
public enum MeetingEventOutboxStatus {
    /** Committed, awaiting claim. The only state the poller will claim. */
    PENDING,
    /** Leased by a poller cycle for publish. Reverted to PENDING if the lease goes stale. */
    CLAIMED,
    /** Delivered to the publisher. Terminal success. */
    PUBLISHED,
    /** Exhausted its retry budget. Terminal failure (needs ops attention). */
    DEAD
}
