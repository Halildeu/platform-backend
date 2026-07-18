package com.example.transcript.repository;

import com.example.transcript.model.TranscriptFinalization;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;

public interface TranscriptFinalizationRepository extends JpaRepository<TranscriptFinalization, UUID> {
    Optional<TranscriptFinalization> findByTenantIdAndMeetingIdAndSessionIdAndFinalizationVersion(
            UUID tenantId, UUID meetingId, UUID sessionId, long finalizationVersion);

    @Query("""
            select f
            from TranscriptFinalization f
            where (f.orgId = :tenantId or (f.orgId is null and f.tenantId = :tenantId))
              and f.meetingId = :meetingId
              and f.sessionId = :sessionId
              and f.finalizationVersion = :finalizationVersion
            """)
    Optional<TranscriptFinalization> findVisibleOccurrence(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId,
            @Param("finalizationVersion") long finalizationVersion);

    @Query("""
            select f
            from TranscriptFinalization f
            where (f.orgId = :tenantId or (f.orgId is null and f.tenantId = :tenantId))
              and f.meetingId = :meetingId
              and f.sessionId = :sessionId
              and f.finalizationVersion = :finalizationVersion
              and f.analysisRunId = :analysisRunId
            """)
    Optional<TranscriptFinalization> findVisibleAnalysisOccurrence(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId,
            @Param("finalizationVersion") long finalizationVersion,
            @Param("analysisRunId") UUID analysisRunId);

    @Query("""
            select f.id
            from TranscriptFinalization f
            where f.createdAt < :cutoff and f.legalHold = false
            order by f.createdAt asc, f.id asc
            """)
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TranscriptFinalization f where f.id in :ids and f.legalHold = false")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select f from TranscriptFinalization f
            where f.tenantId = :tenantId
              and f.meetingId = :meetingId
              and f.sessionId = :sessionId
            """)
    List<TranscriptFinalization> findErasureScopeForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from TranscriptFinalization f
            where f.tenantId = :tenantId
              and f.meetingId = :meetingId
              and f.sessionId = :sessionId
              and f.legalHold = false
            """)
    int deleteErasureScope(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId);

    @Query("""
            select count(f) > 0 from TranscriptFinalization f
            where f.tenantId = :tenantId
              and f.meetingId = :meetingId
              and f.sessionId = :sessionId
              and f.legalHold = true
            """)
    boolean existsLegalHoldForErasure(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId);
}
