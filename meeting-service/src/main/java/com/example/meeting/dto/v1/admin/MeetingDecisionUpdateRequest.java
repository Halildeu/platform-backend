package com.example.meeting.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Update-decision request — Faz 24 (#410).
 *
 * <p>{@code expectedVersion}: optional optimistic-lock precondition (409 on
 * mismatch when supplied; nullable for backward-compat). See
 * {@link MeetingUpdateRequest}.
 */
public record MeetingDecisionUpdateRequest(
        @NotBlank @Size(max = 512) String title,
        @Size(max = 4000) String detail,
        @Size(max = 255) String decidedBySubject,
        Instant decidedAt,
        Long expectedVersion) {
}
