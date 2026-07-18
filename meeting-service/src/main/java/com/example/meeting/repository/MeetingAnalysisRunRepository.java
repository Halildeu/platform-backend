package com.example.meeting.repository;

import com.example.meeting.model.MeetingAnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link MeetingAnalysisRun} — Faz 24 (platform-ai#244 BE-1).
 * See {@link MeetingSessionRepository} for the meeting-scoped + effective-org
 * read convention.
 */
public interface MeetingAnalysisRunRepository extends JpaRepository<MeetingAnalysisRun, UUID> {

    /**
     * Idempotent lookup for an ingestion retry. Scoped by tenant so a run id from
     * another tenant can never be read (or, in the service layer, compared against).
     */
    @Query("""
            select r
            from MeetingAnalysisRun r
            where r.analysisRunId = :analysisRunId
              and r.tenantId = :tenantId
            """)
    Optional<MeetingAnalysisRun> findByAnalysisRunIdAndTenantId(
            @Param("analysisRunId") UUID analysisRunId, @Param("tenantId") UUID tenantId);

    @Query("""
            select r
            from MeetingAnalysisRun r
            where r.analysisRunId = :analysisRunId
              and r.meetingId = :meetingId
              and (r.orgId = :orgId or (r.orgId is null and r.tenantId = :orgId))
            """)
    Optional<MeetingAnalysisRun> findVisibleExactRun(
            @Param("analysisRunId") UUID analysisRunId,
            @Param("meetingId") UUID meetingId,
            @Param("orgId") UUID orgId);

    /** The most recent analysis for a meeting — the canonical read path for the UI. */
    @Query("""
            select r
            from MeetingAnalysisRun r
            where r.meetingId = :meetingId
              and (r.orgId = :orgId or (r.orgId is null and r.tenantId = :orgId))
            order by case when r.finalizedAt is null then 1 else 0 end asc,
                     r.finalizedAt desc,
                     r.finalizationVersion desc,
                     r.generatedAt desc,
                     r.createdAt desc,
                     r.analysisRunId desc
            limit 1
            """)
    Optional<MeetingAnalysisRun> findLatestByMeetingIdVisibleToOrg(
            @Param("meetingId") UUID meetingId, @Param("orgId") UUID orgId);

    @Query("""
            select r
            from MeetingAnalysisRun r
            where r.meetingId = :meetingId
              and (r.orgId = :orgId or (r.orgId is null and r.tenantId = :orgId))
              and r.finalizedAt is not null
              and r.finalizationVersion is not null
            order by r.finalizedAt desc, r.finalizationVersion desc, r.createdAt desc
            limit 1
            """)
    Optional<MeetingAnalysisRun> findLatestCanonicalOccurrence(
            @Param("meetingId") UUID meetingId, @Param("orgId") UUID orgId);

    @Query("""
            select r.analysisRunId
            from MeetingAnalysisRun r
            where r.createdAt < :cutoff and r.legalHold = false
            order by r.createdAt asc, r.analysisRunId asc
            """)
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MeetingAnalysisRun r where r.analysisRunId in :ids and r.legalHold = false")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from MeetingAnalysisRun r
            where r.meetingId = :meetingId
              and r.tenantId = :tenantId
              and r.transcriptSessionId = :sessionId
            """)
    List<MeetingAnalysisRun> findErasureScopeForUpdate(
            @Param("meetingId") UUID meetingId,
            @Param("tenantId") UUID tenantId,
            @Param("sessionId") String sessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from MeetingAnalysisRun r
            where r.meetingId = :meetingId
              and r.tenantId = :tenantId
              and r.transcriptSessionId = :sessionId
              and r.legalHold = false
            """)
    int deleteErasureScope(
            @Param("meetingId") UUID meetingId,
            @Param("tenantId") UUID tenantId,
            @Param("sessionId") String sessionId);

    @Query("""
            select count(r) > 0
            from MeetingAnalysisRun r
            where r.meetingId = :meetingId
              and r.tenantId = :tenantId
              and r.transcriptSessionId = :sessionId
              and r.legalHold = true
            """)
    boolean existsLegalHoldForErasure(
            @Param("meetingId") UUID meetingId,
            @Param("tenantId") UUID tenantId,
            @Param("sessionId") String sessionId);
}
