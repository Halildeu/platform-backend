package com.example.meeting.repository;

import com.example.meeting.model.MeetingAnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** The most recent analysis for a meeting — the canonical read path for the UI. */
    @Query("""
            select r
            from MeetingAnalysisRun r
            where r.meetingId = :meetingId
              and (r.orgId = :orgId or (r.orgId is null and r.tenantId = :orgId))
            order by r.generatedAt desc, r.createdAt desc, r.analysisRunId desc
            limit 1
            """)
    Optional<MeetingAnalysisRun> findLatestByMeetingIdVisibleToOrg(
            @Param("meetingId") UUID meetingId, @Param("orgId") UUID orgId);
}
