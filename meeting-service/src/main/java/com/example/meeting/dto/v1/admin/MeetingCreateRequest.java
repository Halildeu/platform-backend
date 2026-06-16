package com.example.meeting.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Create-meeting request body — Faz 24 (#410).
 *
 * <p>{@code status} is NOT accepted on create (a new meeting is always
 * {@code SCHEDULED}); {@code organizerSubject} defaults to the
 * authenticated subject when blank (resolved server-side). The audit /
 * tenant / org_id columns are derived from the bound tenant context, never
 * the request body.
 */
public record MeetingCreateRequest(
        @NotBlank @Size(max = 512) String title,
        @Size(max = 4000) String description,
        @Size(max = 255) String organizerSubject,
        Instant scheduledStart,
        Instant scheduledEnd) {
}
