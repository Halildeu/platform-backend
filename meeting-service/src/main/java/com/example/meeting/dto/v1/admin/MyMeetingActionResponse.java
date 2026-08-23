package com.example.meeting.dto.v1.admin;

import com.example.meeting.model.MeetingActionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the caller's cross-meeting task list — Faz 24 Görevler dilim-1
 * (gitops#3487). Shape mirrors {@link MeetingActionResponse} plus the owning
 * meeting's title for assignee-centric rendering.
 */
public record MyMeetingActionResponse(
        UUID id,
        UUID meetingId,
        String meetingTitle,
        UUID orgId,
        String description,
        String assigneeSubject,
        MeetingActionStatus status,
        Instant dueAt,
        String createdBySubject,
        Instant createdAt,
        String lastUpdatedBySubject,
        Instant updatedAt,
        Long version) {
}
