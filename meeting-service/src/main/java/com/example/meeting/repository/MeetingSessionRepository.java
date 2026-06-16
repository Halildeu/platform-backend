package com.example.meeting.repository;

import com.example.meeting.model.MeetingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link MeetingSession} — Faz 24 (#410).
 *
 * <p>Children are read both by their owning {@code meetingId} and by the
 * caller's effective-org scope, so a session is only reachable through a
 * meeting the caller may see (same parenthesized-OR effective-org
 * predicate as {@link MeetingRepository}).
 */
public interface MeetingSessionRepository extends JpaRepository<MeetingSession, UUID> {

    @Query("""
            select s
            from MeetingSession s
            where s.meetingId = :meetingId
              and (s.orgId = :orgId or (s.orgId is null and s.tenantId = :orgId))
            order by s.createdAt asc
            """)
    List<MeetingSession> findByMeetingIdVisibleToOrg(
            @Param("meetingId") UUID meetingId, @Param("orgId") UUID orgId);

    @Query("""
            select s
            from MeetingSession s
            where s.id = :id
              and s.meetingId = :meetingId
              and (s.orgId = :orgId or (s.orgId is null and s.tenantId = :orgId))
            """)
    Optional<MeetingSession> findByIdAndMeetingIdVisibleToOrg(
            @Param("id") UUID id,
            @Param("meetingId") UUID meetingId,
            @Param("orgId") UUID orgId);
}
