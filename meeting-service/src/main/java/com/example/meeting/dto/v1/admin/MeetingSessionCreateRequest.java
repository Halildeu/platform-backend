package com.example.meeting.dto.v1.admin;

import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Create-session request — Faz 24 (#410). The owning {@code meetingId} is
 * a path variable, not a body field. {@code transcriptStatus} is not
 * accepted on create (always {@code PENDING}).
 */
public record MeetingSessionCreateRequest(
        @Size(max = 256) String sessionLabel,
        Instant startedAt,
        Instant endedAt,
        @Size(max = 2048) String recordingUri) {
}
