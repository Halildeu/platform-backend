package com.example.meeting.repository;

import com.example.meeting.model.MeetingAction;
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
            where a.id = :id
              and a.meetingId = :meetingId
              and (a.orgId = :orgId or (a.orgId is null and a.tenantId = :orgId))
            """)
    Optional<MeetingAction> findByIdAndMeetingIdVisibleToOrg(
            @Param("id") UUID id,
            @Param("meetingId") UUID meetingId,
            @Param("orgId") UUID orgId);

    @Query("""
            select a.id
            from MeetingAction a
            where a.createdAt < :cutoff
            order by a.createdAt asc, a.id asc
            """)
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MeetingAction a where a.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
