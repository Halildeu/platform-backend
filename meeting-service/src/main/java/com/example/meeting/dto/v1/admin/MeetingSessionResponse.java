package com.example.meeting.dto.v1.admin;

import com.example.meeting.model.TranscriptStatus;

import java.time.Instant;
import java.util.UUID;

/** Session read projection — Faz 24 (#410). */
public record MeetingSessionResponse(
        UUID id,
        UUID meetingId,
        UUID orgId,
        String sessionLabel,
        Instant startedAt,
        Instant endedAt,
        String recordingUri,
        TranscriptStatus transcriptStatus,
        String createdBySubject,
        Instant createdAt,
        String lastUpdatedBySubject,
        Instant updatedAt,
        Long version) {
}
