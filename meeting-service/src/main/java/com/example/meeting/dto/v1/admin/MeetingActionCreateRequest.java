package com.example.meeting.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Create-action request — Faz 24 (#410). Owning {@code meetingId} is a
 * path variable. {@code status} is not accepted on create (always
 * {@code OPEN}).
 */
public record MeetingActionCreateRequest(
        @NotBlank @Size(max = 2000) String description,
        @Size(max = 255) String assigneeSubject,
        Instant dueAt) {
}
