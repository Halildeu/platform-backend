package com.example.meeting.repository;

import com.example.meeting.model.MeetingEventOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link MeetingEventOutbox} — Faz 24 (platform-ai#244 BE-1d).
 *
 * <p>The claim/recover queries are native SQL so they can use Postgres
 * {@code FOR UPDATE SKIP LOCKED} (multi-pod-safe atomic claim) and a set-based
 * {@code UPDATE ... FROM}. They use the Hibernate {@code {h-schema}} placeholder
 * rather than a hard-coded schema, because meeting-service's schema is
 * configurable ({@code MEETING_DB_SCHEMA}, default {@code meeting_service}) — the
 * placeholder is replaced with the configured default schema (incl. trailing dot).
 */
public interface MeetingEventOutboxRepository extends JpaRepository<MeetingEventOutbox, UUID> {

    /**
     * Atomic claim: move up to {@code batchSize} PENDING rows to CLAIMED, stamping
     * this cycle's {@code claimToken}/owner/lease. {@code FOR UPDATE SKIP LOCKED}
     * lets parallel pollers partition the backlog with zero double-claim — a row
     * locked by another cycle is skipped, not blocked on.
     *
     * <p>Crucially, an UNCOMMITTED outbox row (still inside the ingestion
     * transaction) is invisible to this query's snapshot, so the poller can only
     * ever claim COMMITTED rows — commit-after-emit is structural.
     *
     * @return number of rows claimed by this cycle
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        WITH claimed AS (
            SELECT id
            FROM {h-schema}meeting_event_outbox
            WHERE status = 'PENDING'
            ORDER BY created_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        UPDATE {h-schema}meeting_event_outbox o
        SET status = 'CLAIMED',
            claim_token = :claimToken,
            processing_owner = :owner,
            claimed_at = :now,
            lease_expires_at = :leaseUntil,
            updated_at = :now
        FROM claimed
        WHERE o.id = claimed.id
        """, nativeQuery = true)
    int claimBatch(
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("owner") String owner,
            @Param("claimToken") UUID claimToken,
            @Param("batchSize") int batchSize);

    /** Fetch exactly the rows this cycle claimed (multi-pod isolation via claim_token). */
    List<MeetingEventOutbox> findByClaimToken(UUID claimToken);

    /**
     * Crash recovery: revert CLAIMED rows whose lease expired back to PENDING so a
     * poller that died mid-cycle does not strand its rows forever.
     *
     * @return number of rows recovered
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}meeting_event_outbox
        SET status = 'PENDING',
            claim_token = NULL,
            processing_owner = NULL,
            claimed_at = NULL,
            lease_expires_at = NULL,
            updated_at = :now
        WHERE status = 'CLAIMED'
          AND lease_expires_at IS NOT NULL
          AND lease_expires_at <= :now
        """, nativeQuery = true)
    int recoverStaleLeases(@Param("now") Instant now);

    /**
     * Token-fenced terminal success: CLAIMED → PUBLISHED, only if THIS worker still
     * owns the row ({@code status='CLAIMED' AND claim_token=?}). If a slow publish
     * overran its lease and the row was recovered + re-claimed by another worker, the
     * predicate no longer matches and 0 rows update — the stale worker must NOT own the
     * outcome. The caller checks the affected count.
     *
     * @return rows updated (1 = fence held, 0 = lease lost to another worker)
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}meeting_event_outbox
        SET status = 'PUBLISHED',
            published_at = :now,
            claim_token = NULL,
            processing_owner = NULL,
            claimed_at = NULL,
            lease_expires_at = NULL,
            updated_at = :now
        WHERE id = :id
          AND status = 'CLAIMED'
          AND claim_token = :claimToken
        """, nativeQuery = true)
    int markPublishedFenced(@Param("id") UUID id,
                            @Param("claimToken") UUID claimToken,
                            @Param("now") Instant now);

    /**
     * Token-fenced failure: attempts++ → PENDING (retry) or DEAD (budget spent), only
     * while this worker still owns the row. Same fencing rationale as
     * {@link #markPublishedFenced}: a stale worker whose lease was recovered must not
     * reset a row another worker now owns. The DEAD/PENDING decision is computed against
     * the incremented attempts in a single atomic statement.
     *
     * @return rows updated (1 = fence held, 0 = lease lost)
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}meeting_event_outbox
        SET attempts = attempts + 1,
            last_error = :errorClass,
            status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'DEAD' ELSE 'PENDING' END,
            claim_token = NULL,
            processing_owner = NULL,
            claimed_at = NULL,
            lease_expires_at = NULL,
            updated_at = :now
        WHERE id = :id
          AND status = 'CLAIMED'
          AND claim_token = :claimToken
        """, nativeQuery = true)
    int markFailedFenced(@Param("id") UUID id,
                         @Param("claimToken") UUID claimToken,
                         @Param("errorClass") String errorClass,
                         @Param("maxAttempts") int maxAttempts,
                         @Param("now") Instant now);

    /** Test/ops helper: the outbox rows for one analysis run, in a stable order. */
    List<MeetingEventOutbox> findByAggregateIdOrderByEventKeyAsc(UUID aggregateId);

    Optional<MeetingEventOutbox> findByEventKey(String eventKey);

    long countByStatus(com.example.meeting.model.MeetingEventOutboxStatus status);

    // Enqueued in the SAME transaction as the fenced domain PUBLISHED transition.
    @Modifying
    @Query(value = """
        INSERT INTO {h-schema}notification_delivery_outbox (source_id)
        SELECT id FROM {h-schema}meeting_event_outbox
        WHERE id = :id AND status = 'PUBLISHED' AND (event_type IN ('meeting.action.assigned', 'meeting.action.reassigned') OR (:includeSummary AND event_type = 'meeting.summary.ready'))
        ON CONFLICT (source_id) DO NOTHING
        """, nativeQuery = true)
    int enqueueNotification(@Param("id") UUID id, @Param("includeSummary") boolean includeSummary);

    // One locked job, no expiring batch lease. Lock survives until HTTP outcome commits.
    @Query(value = """
        SELECT o.* FROM {h-schema}notification_delivery_outbox n
        JOIN {h-schema}meeting_event_outbox o ON o.id = n.source_id
        WHERE n.status = 'PENDING' AND n.next_attempt_at <= CURRENT_TIMESTAMP
          AND (:includeSummary OR o.event_type <> 'meeting.summary.ready')
        ORDER BY n.next_attempt_at, n.source_id
        LIMIT 1 FOR UPDATE OF n SKIP LOCKED
        """, nativeQuery = true)
    Optional<MeetingEventOutbox> lockNextNotification(@Param("includeSummary") boolean includeSummary);

    // V7 already cascades result -> domain event -> notification job. The job
    // lock prevents deletion from committing during handoff. Do NOT lock the
    // result here: job -> result would reverse the erasure cascade lock order.
    @Query(value = """
        SELECT r.analysis_run_id FROM {h-schema}meeting_analysis_runs r
        JOIN {h-schema}meeting_event_outbox o
          ON r.analysis_run_id = o.aggregate_id AND r.tenant_id = o.tenant_id AND r.meeting_id = o.meeting_id
        WHERE o.id = :id AND o.event_type = 'meeting.summary.ready'
          AND (r.org_id IS NULL OR r.org_id = o.tenant_id)
        """, nativeQuery = true)
    Optional<UUID> findNotificationSource(@Param("id") UUID id);

    // Suppress requests already visible at delivery admission, even before
    // deletion cascades the queued job away.
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM {h-schema}meeting_session_erasure e
            JOIN {h-schema}meeting_analysis_runs r
              ON e.tenant_id = r.tenant_id AND e.meeting_id = r.meeting_id
             AND CAST(e.session_id AS text) = r.transcript_session_id
            JOIN {h-schema}meeting_event_outbox o ON o.aggregate_id = r.analysis_run_id
            WHERE o.id = :id)
        """, nativeQuery = true)
    boolean notificationErasureRequested(@Param("id") UUID id);

    @Modifying
    @Query(value = """
        UPDATE {h-schema}notification_delivery_outbox
        SET status = 'DEAD', last_error = 'SOURCE_UNAVAILABLE' WHERE source_id = :id
        """, nativeQuery = true)
    int notificationSuppressed(@Param("id") UUID id);

    @Modifying
    @Query(value = """
        UPDATE {h-schema}notification_delivery_outbox
        SET status = 'DELIVERED', last_error = NULL WHERE source_id = :id
        """, nativeQuery = true)
    int notificationDelivered(@Param("id") UUID id);

    @Modifying
    @Query(value = """
        UPDATE {h-schema}notification_delivery_outbox
        SET attempts = attempts + 1,
            status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'DEAD' ELSE 'PENDING' END,
            next_attempt_at = :retryAt, last_error = :errorClass
        WHERE source_id = :id
        """, nativeQuery = true)
    int notificationFailed(@Param("id") UUID id, @Param("maxAttempts") int maxAttempts,
                           @Param("retryAt") Instant retryAt, @Param("errorClass") String errorClass);
}
