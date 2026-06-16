package com.example.meeting.dto.v1.admin;

import java.time.Instant;
import java.util.UUID;

/** Decision read projection — Faz 24 (#410). */
public record MeetingDecisionResponse(
        UUID id,
        UUID meetingId,
        UUID orgId,
        String title,
        String detail,
        String decidedBySubject,
        Instant decidedAt,
        String createdBySubject,
        Instant createdAt,
        String lastUpdatedBySubject,
        Instant updatedAt,
        Long version) {
}
