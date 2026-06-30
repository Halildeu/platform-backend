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
        String text,
        int textLength,
        String status,
        Long receivedAtMs,
        String sttLanguage,
        Double durationSeconds,
        String correlationId
) {
}
