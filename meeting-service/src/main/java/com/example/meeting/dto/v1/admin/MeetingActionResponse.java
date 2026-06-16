package com.example.meeting.dto.v1.admin;

import com.example.meeting.model.MeetingActionStatus;

import java.time.Instant;
import java.util.UUID;

/** Action read projection — Faz 24 (#410). */
public record MeetingActionResponse(
        UUID id,
        UUID meetingId,
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
