package com.example.transcript.repository;

import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TranscriptSessionAssociationRepository
        extends JpaRepository<TranscriptSessionAssociation, UUID> {

    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO {h-schema}transcript_session_associations
            (id, tenant_id, org_id, meeting_id, source_system, source_session_id,
             status, resolution_attempts, finalization_version,
             finalization_state, finalization_cycle_version,
             created_at, updated_at, version)
        VALUES (:id, :tenantId, :tenantId, :meetingId, :sourceSystem, :sourceSessionId,
                'PENDING', 0, 0, 'AWAITING_FINISH', 0, :now, :now, 0)
        ON CONFLICT (tenant_id, meeting_id, source_system, source_session_id) DO NOTHING
        """, nativeQuery = true)
    int insertPendingIfAbsent(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sourceSystem") String sourceSystem,
            @Param("sourceSessionId") String sourceSessionId,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        INSERT INTO {h-schema}transcript_session_associations
            (id, tenant_id, org_id, meeting_id, source_system, source_session_id,
             session_id, status, resolution_attempts, finalization_version,
             finalization_state, finalization_cycle_version,
             created_at, updated_at, version)
        VALUES (:id, :tenantId, :tenantId, :meetingId, :sourceSystem, :sourceSessionId,
                :sessionId, 'RESOLVED', 0, 0, 'AWAITING_FINISH', 0,
                :now, :now, 0)
        ON CONFLICT (tenant_id, meeting_id, source_system, source_session_id) DO NOTHING
        """, nativeQuery = true)
    int insertResolvedIfAbsent(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sourceSystem") String sourceSystem,
            @Param("sourceSessionId") String sourceSessionId,
            @Param("sessionId") UUID sessionId,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}transcript_session_associations
        SET status = 'RESOLVED', session_id = :sessionId,
            claim_token = NULL, lease_expires_at = NULL, next_retry_at = NULL,
            last_error_code = NULL, updated_at = :now, version = version + 1
        WHERE id = :id AND session_id IS NULL AND status IN ('PENDING', 'RESOLVING')
        """, nativeQuery = true)
    int bindResolvedFromFinishedEvent(
            @Param("id") UUID id,
            @Param("sessionId") UUID sessionId,
            @Param("now") Instant now);

    Optional<TranscriptSessionAssociation> findByTenantIdAndMeetingIdAndSourceSystemAndSourceSessionId(
            UUID tenantId, UUID meetingId, String sourceSystem, String sourceSessionId);

    List<TranscriptSessionAssociation> findByTenantIdAndMeetingIdAndSessionId(
            UUID tenantId, UUID meetingId, UUID sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from TranscriptSessionAssociation a
            where a.tenantId = :tenantId
              and a.meetingId = :meetingId
              and a.sourceSystem = :sourceSystem
              and a.sourceSessionId = :sourceSessionId
            """)
    Optional<TranscriptSessionAssociation> findSourceForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sourceSystem") String sourceSystem,
            @Param("sourceSessionId") String sourceSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from TranscriptSessionAssociation a
            where a.tenantId = :tenantId
              and a.meetingId = :meetingId
              and a.sessionId = :sessionId
              and a.status = com.example.transcript.model.TranscriptSessionAssociationStatus.RESOLVED
            """)
    Optional<TranscriptSessionAssociation> findCanonicalForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from TranscriptSessionAssociation a where a.id = :id")
    Optional<TranscriptSessionAssociation> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select a.id from TranscriptSessionAssociation a
            where a.status = com.example.transcript.model.TranscriptSessionAssociationStatus.RESOLVED
              and a.finalizationState = com.example.transcript.model.TranscriptFinalizationState.QUIESCING
              and a.quiescenceDueAt <= :now
            order by a.quiescenceDueAt asc, a.id asc
            """)
    List<UUID> findDueFinalizationIds(@Param("now") Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}transcript_session_associations
        SET status = 'RESOLVING',
            claim_token = :claimToken,
            lease_expires_at = :leaseUntil,
            updated_at = :now,
            version = version + 1
        WHERE tenant_id = :tenantId
          AND meeting_id = :meetingId
          AND source_system = :sourceSystem
          AND source_session_id = :sourceSessionId
          AND status = 'PENDING'
          AND (next_retry_at IS NULL OR next_retry_at <= :now)
        """, nativeQuery = true)
    int claimResolution(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sourceSystem") String sourceSystem,
            @Param("sourceSessionId") String sourceSessionId,
            @Param("claimToken") UUID claimToken,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}transcript_session_associations
        SET status = 'PENDING',
            next_retry_at = :nextRetryAt,
            last_error_code = 'LEASE_EXPIRED',
            claim_token = NULL,
            lease_expires_at = NULL,
            updated_at = :now,
            version = version + 1
        WHERE tenant_id = :tenantId
          AND meeting_id = :meetingId
          AND source_system = :sourceSystem
          AND source_session_id = :sourceSessionId
          AND status = 'RESOLVING'
          AND lease_expires_at IS NOT NULL
          AND lease_expires_at <= :now
        """, nativeQuery = true)
    int recoverStaleResolution(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sourceSystem") String sourceSystem,
            @Param("sourceSessionId") String sourceSessionId,
            @Param("nextRetryAt") Instant nextRetryAt,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}transcript_session_associations
        SET status = 'RESOLVED',
            session_id = :sessionId,
            claim_token = NULL,
            lease_expires_at = NULL,
            next_retry_at = NULL,
            last_error_code = NULL,
            updated_at = :now,
            version = version + 1
        WHERE id = :id
          AND status = 'RESOLVING'
          AND claim_token = :claimToken
        """, nativeQuery = true)
    int markResolvedFenced(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("sessionId") UUID sessionId,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query(value = """
        UPDATE {h-schema}transcript_session_associations
        SET resolution_attempts = resolution_attempts + 1,
            status = CASE
                WHEN :forceDead OR resolution_attempts + 1 >= :maxAttempts THEN 'DEAD'
                ELSE 'PENDING'
            END,
            next_retry_at = CASE
                WHEN :forceDead OR resolution_attempts + 1 >= :maxAttempts
                    THEN CAST(NULL AS timestamptz)
                ELSE CAST(:nextRetryAt AS timestamptz)
            END,
            last_error_code = :errorCode,
            claim_token = NULL,
            lease_expires_at = NULL,
            updated_at = :now,
            version = version + 1
        WHERE id = :id
          AND status = 'RESOLVING'
          AND claim_token = :claimToken
        """, nativeQuery = true)
    int markResolutionFailedFenced(
            @Param("id") UUID id,
            @Param("claimToken") UUID claimToken,
            @Param("errorCode") String errorCode,
            @Param("maxAttempts") int maxAttempts,
            @Param("forceDead") boolean forceDead,
            @Param("nextRetryAt") Instant nextRetryAt,
            @Param("now") Instant now);

    @Query("""
            select a from TranscriptSessionAssociation a
            where (a.status = com.example.transcript.model.TranscriptSessionAssociationStatus.PENDING
                    and (a.nextRetryAt is null or a.nextRetryAt <= :now))
               or (a.status = com.example.transcript.model.TranscriptSessionAssociationStatus.RESOLVING
                    and a.leaseExpiresAt <= :now)
            order by a.createdAt asc, a.id asc
            """)
    List<TranscriptSessionAssociation> findDue(@Param("now") Instant now, Pageable pageable);

    long countByStatus(TranscriptSessionAssociationStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from TranscriptSessionAssociation a
            where a.tenantId = :tenantId
              and a.meetingId = :meetingId
              and (a.sessionId = :sessionId
                   or (:sourceSessionId is not null
                       and a.sourceSystem = 'DIRECT_STT'
                       and a.sourceSessionId = :sourceSessionId))
            """)
    int deleteErasureScope(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId,
            @Param("sourceSessionId") String sourceSessionId);
}
