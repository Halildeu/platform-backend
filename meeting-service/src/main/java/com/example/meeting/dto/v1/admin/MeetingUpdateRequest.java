package com.example.meeting.dto.v1.admin;

import com.example.meeting.model.MeetingStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Update-meeting request body — Faz 24 (#410). Unlike create, the status
 * is mutable here (lifecycle transition). All mutable fields are
 * full-replace (PUT semantics).
 *
 * <p>{@code expectedVersion} is an optional optimistic-lock precondition: when
 * supplied, the service rejects the write with 409 if it does not match the
 * persisted {@code @Version} (stale-write / lost-update guard). Nullable for
 * backward-compat — a client that omits it falls back to last-writer-wins.
 */
public record MeetingUpdateRequest(
        @NotBlank @Size(max = 512) String title,
        @Size(max = 4000) String description,
        @NotNull MeetingStatus status,
        @Size(max = 255) String organizerSubject,
        Instant scheduledStart,
        Instant scheduledEnd,
        Long expectedVersion) {
}
