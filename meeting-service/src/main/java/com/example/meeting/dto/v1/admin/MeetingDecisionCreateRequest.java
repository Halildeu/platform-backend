package com.example.meeting.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Create-decision request — Faz 24 (#410). Owning {@code meetingId} is a
 * path variable.
 */
public record MeetingDecisionCreateRequest(
        @NotBlank @Size(max = 512) String title,
        @Size(max = 4000) String detail,
        @Size(max = 255) String decidedBySubject,
        Instant decidedAt) {
}
