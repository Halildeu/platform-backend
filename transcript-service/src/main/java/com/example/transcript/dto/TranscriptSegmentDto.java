package com.example.transcript.dto;

import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection of a transcript segment returned to admin callers. Mirrors
 * the entity field-for-field (transcript text included — this is the read API,
 * and every read that produces this DTO is recorded in the KVKK m.12 access
 * audit by the service).
 */
public record TranscriptSegmentDto(
        UUID id,
        UUID tenantId,
        UUID meetingId,
        UUID sessionId,
        UUID speakerId,
        Double startTime,
        Double endTime,
        String textDraft,
        String textFinal,
        Double confidence,
        TranscriptSegmentStatus status,
        Long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static TranscriptSegmentDto from(TranscriptSegment s) {
        return new TranscriptSegmentDto(
                s.getId(),
                s.getTenantId(),
                s.getMeetingId(),
                s.getSessionId(),
                s.getSpeakerId(),
                s.getStartTime(),
                s.getEndTime(),
                s.getTextDraft(),
                s.getTextFinal(),
                s.getConfidence(),
                s.getStatus(),
                s.getVersion(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
