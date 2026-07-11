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

    /** Test/ops helper: the outbox rows for one analysis run, in a stable order. */
    List<MeetingEventOutbox> findByAggregateIdOrderByEventKeyAsc(UUID aggregateId);

    Optional<MeetingEventOutbox> findByEventKey(String eventKey);

    long countByStatus(com.example.meeting.model.MeetingEventOutboxStatus status);
}
