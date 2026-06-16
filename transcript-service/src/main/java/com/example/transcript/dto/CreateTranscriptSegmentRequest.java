package com.example.transcript.dto;

import com.example.transcript.model.TranscriptSegmentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

/**
 * Create request for a transcript segment.
 *
 * <p>Note: there is NO {@code tenantId} field here — the tenant scope is
 * derived from the authenticated principal ({@code AdminTenantContext}), never
 * from the request body (anti-spoof). {@code meetingId} is a cross-service ref
 * to meeting-service (no FK; the caller is trusted to pass a real meeting id).
 */
public record CreateTranscriptSegmentRequest(
        @NotNull UUID meetingId,
        UUID sessionId,
        UUID speakerId,
        @NotNull @PositiveOrZero Double startTime,
        @NotNull @PositiveOrZero Double endTime,
        String textDraft,
        String textFinal,
        Double confidence,
        TranscriptSegmentStatus status
) {
}
