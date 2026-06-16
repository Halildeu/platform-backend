package com.example.meeting.repository;

import com.example.meeting.model.MeetingDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
            where d.id = :id
              and d.meetingId = :meetingId
              and (d.orgId = :orgId or (d.orgId is null and d.tenantId = :orgId))
            """)
    Optional<MeetingDecision> findByIdAndMeetingIdVisibleToOrg(
            @Param("id") UUID id,
            @Param("meetingId") UUID meetingId,
            @Param("orgId") UUID orgId);
}
