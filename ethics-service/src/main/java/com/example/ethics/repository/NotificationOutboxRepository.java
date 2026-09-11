package com.example.ethics.repository;

import com.example.ethics.model.NotificationOutbox;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, UUID> {

    List<NotificationOutbox> findByClaimTokenOrderByCreatedAtAsc(UUID claimToken);

    long countByStatusIn(Collection<String> statuses);

    long countByStatus(String status);

    // ES-301b (#1153) — the per-(org, event) signal budget, see NotificationSignalBudget.
    @Query(value = """
            SELECT COUNT(*) FROM {h-schema}ethics_notification_signal_window
            WHERE org_id = :orgId AND event_type = :eventType
            """, nativeQuery = true)
    long countSignalWindow(@Param("orgId") UUID orgId, @Param("eventType") String eventType);

    @Modifying
    @Query(value = """
            INSERT INTO {h-schema}ethics_notification_signal_window (org_id, event_type, last_signal_at)
            VALUES (:orgId, :eventType, :at)
            """, nativeQuery = true)
    int openSignalWindow(@Param("orgId") UUID orgId, @Param("eventType") String eventType, @Param("at") Instant at);

    /** Claims the window: 1 when the previous signal is older than the threshold, else 0. */
    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_notification_signal_window
            SET last_signal_at = :now
            WHERE org_id = :orgId
              AND event_type = :eventType
              AND last_signal_at <= :threshold
            """, nativeQuery = true)
    int claimSignalWindow(
            @Param("orgId") UUID orgId,
            @Param("eventType") String eventType,
            @Param("now") Instant now,
            @Param("threshold") Instant threshold);

    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_notification_outbox
            SET status = 'PENDING',
                claim_token = NULL,
                locked_until = NULL,
                next_attempt_at = :now,
                last_error_code = 'LEASE_EXPIRED'
            WHERE status = 'PROCESSING'
              AND locked_until < :now
            """, nativeQuery = true)
    int recoverExpiredLeases(@Param("now") Instant now);

    /**
     * How many signals are stranded, and since when.
     *
     * <p>Requeuing blind is worse than not requeuing: if the cause still
     * stands, every row simply walks back to the same limit and dies again,
     * this time with the operator believing it was handled. The count is read
     * first so the decision is made against a number.
     */
    @Query(value = """
            SELECT count(*), min(created_at), max(created_at)
            FROM {h-schema}ethics_notification_outbox
            WHERE status = 'DEAD_LETTER'
              AND org_id = :orgId
            """, nativeQuery = true)
    Object[] deadLetterSummary(@Param("orgId") UUID orgId);

    /**
     * Return stranded signals to the queue.
     *
     * <p>`DEAD_LETTER` was terminal: a signal whose delivery failed for a
     * reason entirely outside itself — a stale image, a misspelled channel, an
     * unseeded grant — could never be sent again once its attempts ran out.
     * For a whistleblowing product that means the ethics team was never
     * alerted to those reports and never could be, while the reports
     * themselves sat there intact.
     *
     * <p>The attempt counter resets so the row gets a real chance, but the row
     * identity — and therefore the idempotency key the orchestrator dedupes on
     * — is untouched: a signal that did reach the recipient before dying
     * cannot arrive twice.
     */
    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_notification_outbox
            SET status = 'PENDING',
                attempt_count = 0,
                claim_token = NULL,
                locked_until = NULL,
                next_attempt_at = :now,
                last_error_code = NULL
            WHERE id IN (
                SELECT id
                FROM {h-schema}ethics_notification_outbox
                WHERE status = 'DEAD_LETTER'
                  AND org_id = :orgId
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int requeueDeadLetters(
            @Param("orgId") UUID orgId,
            @Param("now") Instant now,
            @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_notification_outbox
            SET status = 'PROCESSING',
                claim_token = :claimToken,
                locked_until = :lockedUntil,
                attempt_count = attempt_count + 1,
                last_error_code = NULL
            WHERE id IN (
                SELECT id
                FROM {h-schema}ethics_notification_outbox
                WHERE status = 'PENDING'
                  AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
            )
            """, nativeQuery = true)
    int claimDue(
            @Param("claimToken") UUID claimToken,
            @Param("now") Instant now,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("batchSize") int batchSize);

    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_notification_outbox
            SET status = 'DELIVERED',
                delivered_at = :deliveredAt,
                claim_token = NULL,
                locked_until = NULL,
                next_attempt_at = NULL,
                last_error_code = NULL
            WHERE id = :id
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
              AND locked_until = :lockedUntil
            """, nativeQuery = true)
    int markDelivered(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("deliveredAt") Instant deliveredAt);

    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_notification_outbox
            SET status = 'PENDING',
                claim_token = NULL,
                locked_until = NULL,
                next_attempt_at = :nextAttemptAt,
                last_error_code = :errorCode
            WHERE id = :id
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
              AND locked_until = :lockedUntil
            """, nativeQuery = true)
    int markRetry(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("errorCode") String errorCode);

    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_notification_outbox
            SET status = 'DEAD_LETTER',
                claim_token = NULL,
                locked_until = NULL,
                next_attempt_at = NULL,
                last_error_code = :errorCode
            WHERE id = :id
              AND status = 'PROCESSING'
              AND claim_token = :claimToken
              AND locked_until = :lockedUntil
            """, nativeQuery = true)
    int markDeadLetter(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("lockedUntil") Instant lockedUntil,
            @Param("errorCode") String errorCode);

    /** Whether a signal of this type already exists for the org since the given instant. */
    boolean existsByOrgIdAndEventTypeAndCreatedAtAfter(UUID orgId, String eventType, Instant since);
}
