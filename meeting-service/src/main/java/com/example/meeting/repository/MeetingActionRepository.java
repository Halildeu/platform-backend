package com.example.meeting.repository;

import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingActionStatus;
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
 * Repository for {@link MeetingAction} — Faz 24 (#410). See
 * {@link MeetingSessionRepository} for the meeting-scoped + effective-org
 * read convention.
 */
public interface MeetingActionRepository extends JpaRepository<MeetingAction, UUID> {

    @Query("""
            select a
            from MeetingAction a
            where a.meetingId = :meetingId
              and (a.orgId = :orgId or (a.orgId is null and a.tenantId = :orgId))
            order by a.createdAt asc
            """)
    List<MeetingAction> findByMeetingIdVisibleToOrg(
            @Param("meetingId") UUID meetingId, @Param("orgId") UUID orgId);

    @Query("""
            select a
            from MeetingAction a
            where a.analysisRunId = :analysisRunId
              and a.meetingId = :meetingId
              and (a.orgId = :orgId or (a.orgId is null and a.tenantId = :orgId))
            order by a.ordinal asc, a.id asc
            """)
    List<MeetingAction> findByAnalysisRunIdAndMeetingIdVisibleToOrg(
            @Param("analysisRunId") UUID analysisRunId,
            @Param("meetingId") UUID meetingId,
            @Param("orgId") UUID orgId);

    @Query("""
            select a
            from MeetingAction a
            where a.id = :id
              and a.meetingId = :meetingId
              and (a.orgId = :orgId or (a.orgId is null and a.tenantId = :orgId))
            """)
    Optional<MeetingAction> findByIdAndMeetingIdVisibleToOrg(
            @Param("id") UUID id,
            @Param("meetingId") UUID meetingId,
            @Param("orgId") UUID orgId);

    /**
     * Assignee-centric cross-meeting view ("Görevlerim", gitops#3487). Same
     * effective-org visibility convention as the meeting-scoped reads; the
     * meeting join is tenant-safe (composite (id, tenant) ownership). Rows
     * with a due date come first (soonest first), undated ones follow by age.
     */
    @Query("""
            select new com.example.meeting.repository.MyActionProjection(a, m.title)
            from MeetingAction a, Meeting m
            where m.id = a.meetingId
              and m.tenantId = a.tenantId
              and a.assigneeSubject = :assigneeSubject
              and a.status in :statuses
              and (a.orgId = :orgId or (a.orgId is null and a.tenantId = :orgId))
            order by case when a.dueAt is null then 1 else 0 end asc,
                     a.dueAt asc, a.createdAt asc, a.id asc
            """)
    List<MyActionProjection> findByAssigneeVisibleToOrgAndStatusIn(
            @Param("assigneeSubject") String assigneeSubject,
            @Param("orgId") UUID orgId,
            @Param("statuses") Collection<MeetingActionStatus> statuses);

    @Query("""
            select a.id
            from MeetingAction a
            where a.createdAt < :cutoff
              and a.analysisRunId is null
            order by a.createdAt asc, a.id asc
            """)
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MeetingAction a where a.id in :ids and a.analysisRunId is null")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
