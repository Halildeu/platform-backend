package com.example.audiogateway.dto;

/**
 * Recorder consent audit response.
 *
 * @param acceptedAtMs server-side acceptance timestamp; client-supplied time is
 *                     intentionally ignored for legal/audit authority
 */
public record RecordingConsentResponse(
        String meetingId,
        String captureId,
        String consentVersion,
        String consentTextHash,
        String locale,
        String correlationId,
        long acceptedAtMs
) {
}
