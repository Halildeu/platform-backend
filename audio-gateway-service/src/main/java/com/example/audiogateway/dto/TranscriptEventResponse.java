package com.example.audiogateway.dto;

/**
 * Client-facing live transcript event. The response carries transcript text for
 * the authenticated session owner only; it never includes raw audio or bearer data.
 */
public record TranscriptEventResponse(
        String eventId,
        String sessionId,
        String meetingId,
        long chunkSeq,
        long chunkStartedAtMs,
        long windowSeq,
        long firstChunkSeq,
        long lastChunkSeq,
        long windowStartedAtMs,
        long windowEndedAtMs,
        int audioDurationMs,
        String flushReason,
        String text,
        int textLength,
        String status,
        Long receivedAtMs,
        String sttLanguage,
        Double durationSeconds,
        String correlationId
) {
}
