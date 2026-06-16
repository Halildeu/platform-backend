package com.example.meeting.dto.v1.admin;

import com.example.meeting.model.TranscriptStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Update-session request — Faz 24 (#410). {@code transcriptStatus} is
 * mutable here.
 *
 * <p>{@code expectedVersion}: optional optimistic-lock precondition (409 on
 * mismatch when supplied; nullable for backward-compat). See
 * {@link MeetingUpdateRequest}.
 */
public record MeetingSessionUpdateRequest(
        @Size(max = 256) String sessionLabel,
        Instant startedAt,
        Instant endedAt,
        @Size(max = 2048) String recordingUri,
        @NotNull TranscriptStatus transcriptStatus,
        Long expectedVersion) {
}
