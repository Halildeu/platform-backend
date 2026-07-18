package com.example.meeting.repository;

import com.example.meeting.model.MeetingSessionErasure;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface MeetingSessionErasureRepository extends JpaRepository<MeetingSessionErasure, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from MeetingSessionErasure e where e.sessionId = :sessionId")
    Optional<MeetingSessionErasure> findBySessionIdForUpdate(@Param("sessionId") UUID sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from MeetingSessionErasure e
            where e.sessionId = :sessionId and e.claimToken = :claimToken
            """)
    Optional<MeetingSessionErasure> findClaimedForUpdate(
            @Param("sessionId") UUID sessionId,
            @Param("claimToken") UUID claimToken);

    boolean existsByTenantIdAndMeetingIdAndSessionId(UUID tenantId, UUID meetingId, UUID sessionId);

    boolean existsByTenantIdAndMeetingIdAndSourceSessionHash(
            UUID tenantId, UUID meetingId, String sourceSessionHash);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        WITH claimed AS (
            SELECT session_id
            FROM {h-schema}meeting_session_erasure
            WHERE status IN ('PENDING', 'HELD')
              AND next_attempt_at <= :now
            ORDER BY requested_at, session_id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
        )
        UPDATE {h-schema}meeting_session_erasure e
        SET status = 'ACTIVE', claim_token = :claimToken,
            processing_owner = :owner, claimed_at = :now,
            lease_expires_at = :leaseUntil, attempts = attempts + 1,
            last_error_code = NULL, updated_at = :now, version = version + 1
        FROM claimed
        WHERE e.session_id = claimed.session_id
        """, nativeQuery = true)
    int claimBatch(
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("owner") String owner,
            @Param("claimToken") UUID claimToken,
            @Param("batchSize") int batchSize);

    List<MeetingSessionErasure> findByClaimToken(UUID claimToken);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}meeting_session_erasure
        SET status = 'PENDING', claim_token = NULL, processing_owner = NULL,
            claimed_at = NULL, lease_expires_at = NULL,
            next_attempt_at = :retryAt, last_error_code = 'LEASE_EXPIRED',
            updated_at = :now, version = version + 1
        WHERE status = 'ACTIVE'
          AND lease_expires_at IS NOT NULL
          AND lease_expires_at <= :now
        """, nativeQuery = true)
    int recoverStaleLeases(@Param("now") Instant now, @Param("retryAt") Instant retryAt);
}
