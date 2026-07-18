package com.example.transcript.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable finalized transcript occurrence consumed by meeting-ai. */
public record CanonicalTranscriptSnapshotDto(
        UUID tenantId,
        UUID meetingId,
        UUID sessionId,
        long finalizationVersion,
        Instant finalizedAt,
        String state,
        String transcript,
        String transcriptSha256,
        int segmentCount,
        List<CanonicalTranscriptSegmentDto> segments) { }
