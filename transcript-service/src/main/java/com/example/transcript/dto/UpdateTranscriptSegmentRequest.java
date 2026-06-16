package com.example.transcript.dto;

import com.example.transcript.model.TranscriptSegmentStatus;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

/**
 * Partial update request for a transcript segment.
 *
 * <p>All content fields are nullable; a {@code null} field leaves the existing
 * value unchanged. {@code expectedVersion} is the optimistic-lock precondition:
 * when non-null, the service rejects the update with {@code 409 Conflict} (via
 * {@link org.springframework.dao.OptimisticLockingFailureException}) if it does
 * not match the persisted {@code version}.
 */
public record UpdateTranscriptSegmentRequest(
        @PositiveOrZero Double startTime,
        @PositiveOrZero Double endTime,
        String textDraft,
        String textFinal,
        Double confidence,
        TranscriptSegmentStatus status,
        UUID speakerId,
        Long expectedVersion
) {
}
