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

    // Enqueued in the SAME transaction as the fenced domain PUBLISHED transition.
    @Modifying
    @Query(value = """
        INSERT INTO {h-schema}notification_delivery_outbox (source_id)
        SELECT id FROM {h-schema}transcript_event_outbox
        WHERE id = :id AND status = 'PUBLISHED' AND event_type = 'meeting.transcript.ready'
        ON CONFLICT (source_id) DO NOTHING
        """, nativeQuery = true)
    int enqueueNotification(@Param("id") UUID id);

    // One locked job, no expiring batch lease. Lock survives until HTTP outcome commits.
    @Query(value = """
        SELECT o.* FROM {h-schema}notification_delivery_outbox n
        JOIN {h-schema}transcript_event_outbox o ON o.id = n.source_id
        WHERE n.status = 'PENDING' AND n.next_attempt_at <= CURRENT_TIMESTAMP
        ORDER BY n.next_attempt_at, n.source_id
        LIMIT 1 FOR UPDATE OF n SKIP LOCKED
        """, nativeQuery = true)
    Optional<TranscriptEventOutbox> lockNextNotification();

    // The event survives erasure. Lock its EXACT occurrence, not another version
    // of the session, until the HTTP handoff commits. Erasure/retention deletes
    // cannot complete while this row is held; neither takes a notification lock.
    @Query(value = """
        SELECT f.id FROM {h-schema}transcript_finalizations f
        JOIN {h-schema}transcript_event_outbox o
          ON f.tenant_id = o.tenant_id AND f.meeting_id = o.meeting_id
         AND f.session_id = o.aggregate_id
         AND CAST(f.analysis_run_id AS text) = CAST(o.payload AS jsonb)->>'analysisRunId'
         AND CAST(f.finalization_version AS text) = CAST(o.payload AS jsonb)->>'finalizationVersion'
        WHERE o.id = :id AND o.event_type = 'meeting.transcript.ready'
          AND (f.org_id IS NULL OR f.org_id = o.tenant_id)
        FOR SHARE OF f
        """, nativeQuery = true)
    Optional<UUID> lockNotificationSource(@Param("id") UUID id);

    // Separate statement AFTER the row lock: observes a prepare that committed
    // while acquisition waited, even when prepare did not change the source row.
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM {h-schema}transcript_session_erasure_tombstones t
            JOIN {h-schema}transcript_event_outbox o
              ON t.tenant_id = o.tenant_id AND t.meeting_id = o.meeting_id AND t.session_id = o.aggregate_id
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
