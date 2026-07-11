package com.example.meeting.repository;

import com.example.meeting.model.MeetingDecision;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link MeetingDecision} — Faz 24 (#410). See
 * {@link MeetingSessionRepository} for the meeting-scoped + effective-org
 * read convention.
 */
public interface MeetingDecisionRepository extends JpaRepository<MeetingDecision, UUID> {

    @Query("""
            select d
            from MeetingDecision d
            where d.meetingId = :meetingId
              and (d.orgId = :orgId or (d.orgId is null and d.tenantId = :orgId))
            order by d.createdAt asc
            """)
    List<MeetingDecision> findByMeetingIdVisibleToOrg(
            @Param("meetingId") UUID meetingId, @Param("orgId") UUID orgId);

    @Query("""
            select d
            from MeetingDecision d
            where d.analysisRunId = :analysisRunId
              and d.meetingId = :meetingId
              and (d.orgId = :orgId or (d.orgId is null and d.tenantId = :orgId))
            order by d.ordinal asc, d.id asc
            """)
    List<MeetingDecision> findByAnalysisRunIdAndMeetingIdVisibleToOrg(
            @Param("analysisRunId") UUID analysisRunId,
            @Param("meetingId") UUID meetingId,
            @Param("orgId") UUID orgId);

    @Query("""
            select d
            from MeetingDecision d
            where d.id = :id
              and d.meetingId = :meetingId
              and (d.orgId = :orgId or (d.orgId is null and d.tenantId = :orgId))
            """)
    Optional<MeetingDecision> findByIdAndMeetingIdVisibleToOrg(
            @Param("id") UUID id,
            @Param("meetingId") UUID meetingId,
            @Param("orgId") UUID orgId);

    @Query("""
            select d.id
            from MeetingDecision d
            where d.createdAt < :cutoff
            order by d.createdAt asc, d.id asc
            """)
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MeetingDecision d where d.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
