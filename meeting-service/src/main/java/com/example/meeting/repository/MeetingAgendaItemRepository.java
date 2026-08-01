package com.example.meeting.repository;

import com.example.meeting.model.MeetingAgendaItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-safe repository for ordered meeting agenda items. */
public interface MeetingAgendaItemRepository extends JpaRepository<MeetingAgendaItem, UUID> {

    @Query("""
            select item
            from MeetingAgendaItem item
            where item.meetingId = :meetingId
              and (item.orgId = :orgId or (item.orgId is null and item.tenantId = :orgId))
            order by item.position asc, item.createdAt asc, item.id asc
            """)
    List<MeetingAgendaItem> findByMeetingIdVisibleToOrg(
            @Param("meetingId") UUID meetingId, @Param("orgId") UUID orgId);

    @Query("""
            select item
            from MeetingAgendaItem item
            where item.id = :id
              and item.meetingId = :meetingId
              and (item.orgId = :orgId or (item.orgId is null and item.tenantId = :orgId))
            """)
    Optional<MeetingAgendaItem> findByIdAndMeetingIdVisibleToOrg(
            @Param("id") UUID id,
            @Param("meetingId") UUID meetingId,
            @Param("orgId") UUID orgId);
}
