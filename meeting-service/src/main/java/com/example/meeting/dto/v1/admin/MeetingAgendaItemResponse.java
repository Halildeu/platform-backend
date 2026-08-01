package com.example.meeting.dto.v1.admin;

import com.example.meeting.model.MeetingAgendaItemStatus;

import java.time.Instant;
import java.util.UUID;

/** Canonical read projection for a meeting agenda item. */
public record MeetingAgendaItemResponse(
        UUID id,
        UUID meetingId,
        UUID orgId,
        Integer position,
        String title,
        String detail,
        String ownerSubject,
        Integer plannedDurationMinutes,
        MeetingAgendaItemStatus status,
        String createdBySubject,
        Instant createdAt,
        String lastUpdatedBySubject,
        Instant updatedAt,
        Long version) {
}
