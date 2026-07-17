package com.example.auditconsumer.repository;

import com.example.auditconsumer.model.ConsentEventOutbox;
import com.example.auditconsumer.model.ConsentEventOutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsentEventOutboxRepository extends JpaRepository<ConsentEventOutbox, UUID> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
        WITH claimed AS (
            SELECT id
            FROM {h-schema}consent_event_outbox
            WHERE status = 'PENDING'
              AND next_attempt_at <= :now
            ORDER BY created_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        UPDATE {h-schema}consent_event_outbox o
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

    List<ConsentEventOutbox> findByClaimToken(UUID claimToken);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}consent_event_outbox
        SET attempts = attempts + 1,
            last_error = 'LEASE_EXPIRED',
            status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'DEAD' ELSE 'PENDING' END,
            next_attempt_at = CASE WHEN attempts + 1 >= :maxAttempts
                THEN CAST(:now AS TIMESTAMP WITH TIME ZONE)
                ELSE CAST(:retryAt AS TIMESTAMP WITH TIME ZONE) END,
            claim_token = NULL,
            processing_owner = NULL,
            claimed_at = NULL,
            lease_expires_at = NULL,
            updated_at = :now
        WHERE status = 'CLAIMED'
          AND lease_expires_at IS NOT NULL
          AND lease_expires_at <= :now
        """, nativeQuery = true)
    int recoverStaleLeases(
            @Param("now") Instant now,
            @Param("retryAt") Instant retryAt,
            @Param("maxAttempts") int maxAttempts);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}consent_event_outbox
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
    int markPublishedFenced(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}consent_event_outbox
        SET attempts = attempts + 1,
            last_error = :errorClass,
            status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'DEAD' ELSE 'PENDING' END,
            next_attempt_at = CASE WHEN attempts + 1 >= :maxAttempts
                THEN CAST(:now AS TIMESTAMP WITH TIME ZONE)
                ELSE CAST(:retryAt AS TIMESTAMP WITH TIME ZONE) END,
            claim_token = NULL,
            processing_owner = NULL,
            claimed_at = NULL,
            lease_expires_at = NULL,
            updated_at = :now
        WHERE id = :id
          AND status = 'CLAIMED'
          AND claim_token = :claimToken
        """, nativeQuery = true)
    int markFailedFenced(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("errorClass") String errorClass,
            @Param("maxAttempts") int maxAttempts,
            @Param("retryAt") Instant retryAt,
            @Param("now") Instant now);

    Optional<ConsentEventOutbox> findByEventKey(String eventKey);

    long countByStatus(ConsentEventOutboxStatus status);
}
