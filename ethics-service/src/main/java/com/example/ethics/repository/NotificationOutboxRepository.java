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
}
