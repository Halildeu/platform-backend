package com.example.audiogateway.dto;

/** Accepted revocation audit command. Durable event publication is asynchronous. */
public record RecordingConsentRevocationResponse(
        String meetingId,
        String captureId,
        String consentVersion,
        long consentRevision,
        String reasonCode,
        String correlationId,
        long revokedAtMs
) {
}
