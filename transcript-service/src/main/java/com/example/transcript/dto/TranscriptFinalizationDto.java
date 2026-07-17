package com.example.transcript.dto;

import java.time.Instant;
import java.util.UUID;

/** Content-free result of one canonical transcript finalization occurrence. */
public record TranscriptFinalizationDto(
        UUID id,
        UUID meetingId,
        UUID sessionId,
        long finalizationVersion,
        int segmentCount,
        Instant finalizedAt,
        String eventKey) {
}
