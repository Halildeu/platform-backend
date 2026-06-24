package com.example.audiogateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Recorder consent audit request.
 *
 * <p>The gateway derives actor/tenant/user/server time from the JWT and server
 * clock. Clients send only the canonical meeting/capture identifiers and the
 * immutable consent text digest they displayed.
 */
public record RecordingConsentRequest(

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^"
                + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                + "$",
                message = "meetingId must be a meeting-service UUID")
        String meetingId,

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^"
                + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                + "$",
                message = "captureId must be a UUID generated before recording starts")
        String captureId,

        @NotBlank
        @Size(min = 1, max = 64)
        @Pattern(regexp = "^[A-Za-z0-9._:\\-]{1,64}$",
                message = "consentVersion must be an opaque version token")
        String consentVersion,

        @NotBlank
        @Pattern(regexp = "^sha256:[a-f0-9]{64}$",
                message = "consentTextHash must be sha256:<64 lowercase hex>")
        String consentTextHash,

        @NotBlank
        @Size(min = 2, max = 10)
        @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$",
                message = "locale must be ISO language or language-region")
        String locale
) {
}
