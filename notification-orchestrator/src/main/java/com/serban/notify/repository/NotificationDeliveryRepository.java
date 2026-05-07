package com.serban.notify.repository;

import com.serban.notify.domain.NotificationDelivery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findByIntentId(String intentId);

    /**
     * Per-target idempotent lookup (Codex 019df9ef P2 absorb).
     *
     * <p>{@code uq_delivery_intent_channel_recipient (intent_id, channel,
     * recipient_hash)} unique constraint guarantees at most 1 row.
     */
    Optional<NotificationDelivery> findByIntentIdAndChannelAndRecipientHash(
        String intentId, String channel, String recipientHash
    );

    /**
     * Lookup by provider message id (Faz 23.4 PR-F DLR ingest).
     *
     * <p>Used by DLR (Delivery Receipt) callback ingestion: provider posts
     * status update with original message id (e.g. {@code "netgsm-{jobid}"}
     * for NetGSM); we update the existing delivery row.
     *
     * <p>Not strictly UNIQUE in schema (provider_msg_id is nullable for
     * pending deliveries), but each successful send produces a unique id.
     * Returns first match if multiple hypothetically exist (defensive;
     * caller treats > 1 match as data error).
     */
    Optional<NotificationDelivery> findFirstByProviderMsgId(String providerMsgId);

    @Query("SELECT d FROM NotificationDelivery d WHERE d.status = :status " +
           "AND d.nextRetryAt <= :now")
    List<NotificationDelivery> findDueForRetry(
        @Param("status") NotificationDelivery.Status status,
        @Param("now") OffsetDateTime now,
        Pageable pageable
    );

    long countByStatus(NotificationDelivery.Status status);

    /**
     * Atomic native claim for RetryWorker (Codex 019dfa47 Q1 absorb).
     *
     * <p>Selects RETRY deliveries whose {@code next_retry_at} ≤ now AND lease
     * expired (or null), locks them via {@code SKIP LOCKED}, sets new lease
     * deadline, returns count of claimed rows. Caller fetches with
     * {@link #findByStatusAndProcessingLeaseUntilGreaterThan}.
     */
    @Modifying
    @Query(value = """
        WITH claimed AS (
            SELECT id
            FROM notify.notification_delivery
            WHERE status = 'RETRY'
              AND next_retry_at IS NOT NULL
              AND next_retry_at <= :now
              AND (processing_lease_until IS NULL OR processing_lease_until <= :now)
            ORDER BY next_retry_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        UPDATE notify.notification_delivery d
        SET processing_lease_until = :leaseUntil,
            claim_token = :claimToken,
            updated_at = :now
        FROM claimed
        WHERE d.id = claimed.id
        """, nativeQuery = true)
    int claimDueForRetry(
        @Param("now") OffsetDateTime now,
        @Param("leaseUntil") OffsetDateTime leaseUntil,
        @Param("claimToken") String claimToken,
        @Param("batchSize") int batchSize
    );

    /**
     * Find deliveries claimed in this exact cycle (Codex 019dfa47 iter-1 P0 absorb).
     * Multi-pod isolation: only this cycle's claims.
     */
    List<NotificationDelivery> findByClaimToken(String claimToken);

    /**
     * Find RETRY deliveries that exceeded max attempts (DLQ candidates).
     */
    @Query("""
        SELECT d FROM NotificationDelivery d
         WHERE d.status = com.serban.notify.domain.NotificationDelivery.Status.RETRY
           AND d.attemptCount >= :maxAttempts
        """)
    List<NotificationDelivery> findExhaustedRetries(
        @Param("maxAttempts") int maxAttempts, Pageable pageable
    );
}
