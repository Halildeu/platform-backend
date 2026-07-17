package com.example.audiogateway.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** User-initiated withdrawal of a previously recorded recorder consent. */
public record RecordingConsentRevocationRequest(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "meetingId must be a meeting-service UUID")
        String meetingId,

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "captureId must be the consent capture UUID")
        String captureId,

        @NotBlank
        @Size(min = 1, max = 64)
        @Pattern(regexp = "^[A-Za-z0-9._:\\-]{1,64}$",
                message = "consentVersion must be an opaque version token")
        String consentVersion,

        @Min(value = 2, message = "consentRevision must be 2")
        @Max(value = 2, message = "consentRevision must be 2")
        long consentRevision,

        @NotBlank
        @Size(min = 13, max = 13)
        @Pattern(regexp = "^USER_WITHDREW$",
                message = "reasonCode must be USER_WITHDREW")
        String reasonCode
) {
}
