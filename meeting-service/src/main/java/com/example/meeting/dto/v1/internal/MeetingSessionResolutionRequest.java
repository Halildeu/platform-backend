package com.example.meeting.dto.v1.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** Thin service-to-service lookup key for a recorder session association. */
public record MeetingSessionResolutionRequest(
        @NotNull UUID tenantId,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$") String externalSessionId) {
}
