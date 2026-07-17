package com.example.meeting.dto.v1.admin;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Idempotent attended-recording lifecycle state keyed by the audio gateway
 * session identifier. An absent {@code endedAt} represents an active recording;
 * a present value represents the monotonic finished state. Lifecycle finish
 * moves a pending transcript into {@code PROCESSING}; the transcript consumer
 * remains the owner of finalization into {@code COMPLETED} or {@code FAILED}.
 */
public record RecordingLifecycleSyncRequest(
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9._:-]+$")
        String externalSessionId,
        @NotNull Instant startedAt,
        Instant endedAt) {

    @AssertTrue(message = "endedAt must not be before startedAt")
    public boolean isChronological() {
        return startedAt == null || endedAt == null || !endedAt.isBefore(startedAt);
    }
}
