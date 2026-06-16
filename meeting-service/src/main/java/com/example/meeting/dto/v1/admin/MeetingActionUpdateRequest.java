package com.example.meeting.dto.v1.admin;

import com.example.meeting.model.MeetingActionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Update-action request — Faz 24 (#410). {@code status} is mutable.
 *
 * <p>{@code expectedVersion}: optional optimistic-lock precondition (409 on
 * mismatch when supplied; nullable for backward-compat). See
 * {@link MeetingUpdateRequest}.
 */
public record MeetingActionUpdateRequest(
        @NotBlank @Size(max = 2000) String description,
        @Size(max = 255) String assigneeSubject,
        @NotNull MeetingActionStatus status,
        Instant dueAt,
        Long expectedVersion) {
}
