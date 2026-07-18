package com.example.transcript.repository;

import com.example.transcript.model.TranscriptEventOutbox;
import com.example.transcript.model.TranscriptEventOutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TranscriptEventOutboxRepository extends JpaRepository<TranscriptEventOutbox, UUID> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
        WITH claimed AS (
            SELECT id
            FROM {h-schema}transcript_event_outbox
            WHERE status = 'PENDING'
              AND next_attempt_at <= :now
            ORDER BY created_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        UPDATE {h-schema}transcript_event_outbox o
        SET status = 'CLAIMED', claim_token = :claimToken,
            processing_owner = :owner, claimed_at = :now,
            lease_expires_at = :leaseUntil, updated_at = :now,
            version = version + 1
        FROM claimed
        WHERE o.id = claimed.id
        """, nativeQuery = true)
    int claimBatch(
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("owner") String owner,
            @Param("claimToken") UUID claimToken,
            @Param("batchSize") int batchSize);

    List<TranscriptEventOutbox> findByClaimToken(UUID claimToken);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}transcript_event_outbox
        SET last_error = 'LEASE_EXPIRED',
            status = 'PENDING',
            next_attempt_at = CAST(:retryAt AS TIMESTAMP WITH TIME ZONE),
            claim_token = NULL, processing_owner = NULL,
            claimed_at = NULL, lease_expires_at = NULL, updated_at = :now,
            version = version + 1
        WHERE status = 'CLAIMED'
          AND lease_expires_at IS NOT NULL
          AND lease_expires_at <= :now
        """, nativeQuery = true)
    int recoverStaleLeases(
            @Param("now") Instant now,
            @Param("retryAt") Instant retryAt);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}transcript_event_outbox
        SET status = 'PUBLISHED', published_at = :now,
            claim_token = NULL, processing_owner = NULL,
            claimed_at = NULL, lease_expires_at = NULL, updated_at = :now,
            version = version + 1
        WHERE id = :id AND status = 'CLAIMED' AND claim_token = :claimToken
        """, nativeQuery = true)
    int markPublishedFenced(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}transcript_event_outbox
        SET attempts = attempts + 1,
            last_error = :errorClass,
            status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'DEAD' ELSE 'PENDING' END,
            next_attempt_at = CASE WHEN attempts + 1 >= :maxAttempts
                THEN CAST(:now AS TIMESTAMP WITH TIME ZONE)
                ELSE CAST(:retryAt AS TIMESTAMP WITH TIME ZONE) END,
            claim_token = NULL, processing_owner = NULL,
            claimed_at = NULL, lease_expires_at = NULL, updated_at = :now,
            version = version + 1
        WHERE id = :id AND status = 'CLAIMED' AND claim_token = :claimToken
        """, nativeQuery = true)
    int markFailedFenced(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("errorClass") String errorClass,
            @Param("maxAttempts") int maxAttempts,
            @Param("retryAt") Instant retryAt,
            @Param("now") Instant now);

    Optional<TranscriptEventOutbox> findByEventKey(String eventKey);

    long countByStatus(TranscriptEventOutboxStatus status);
}
