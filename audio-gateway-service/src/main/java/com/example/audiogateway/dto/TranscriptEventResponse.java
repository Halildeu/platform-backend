package com.example.audiogateway.dto;

import java.util.List;

/**
 * Client-facing live transcript event. The response carries transcript text for
 * the authenticated session owner only; it never includes raw audio or bearer data.
 *
 * <p>{@code status} distinguishes the two kinds a client receives:
 * {@code DRAFT} is a raw committed chunk cut on an acoustic boundary, {@code UTTERANCE}
 * is the readable line the gateway assembled from consecutive chunks. Both are emitted;
 * a client renders permanent lines from {@code UTTERANCE} and treats {@code DRAFT} as
 * volatile. {@code assemblyReason} and {@code sourceEventIds} are populated only for
 * {@code UTTERANCE} and are the audit trail back to the fragments it folded.
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
        String correlationId,
        String assemblyReason,
        List<String> sourceEventIds
) {

    public TranscriptEventResponse {
        sourceEventIds = sourceEventIds == null ? List.of() : List.copyOf(sourceEventIds);
    }

    /** A raw committed chunk — carries no assembly provenance. */
    public TranscriptEventResponse(
            final String eventId,
            final String sessionId,
            final String meetingId,
            final long chunkSeq,
            final long chunkStartedAtMs,
            final long windowSeq,
            final long firstChunkSeq,
            final long lastChunkSeq,
            final long windowStartedAtMs,
            final long windowEndedAtMs,
            final int audioDurationMs,
            final String flushReason,
            final String text,
            final int textLength,
            final String status,
            final Long receivedAtMs,
            final String sttLanguage,
            final Double durationSeconds,
            final String correlationId) {
        this(
                eventId,
                sessionId,
                meetingId,
                chunkSeq,
                chunkStartedAtMs,
                windowSeq,
                firstChunkSeq,
                lastChunkSeq,
                windowStartedAtMs,
                windowEndedAtMs,
                audioDurationMs,
                flushReason,
                text,
                textLength,
                status,
                receivedAtMs,
                sttLanguage,
                durationSeconds,
                correlationId,
                null,
                List.of());
    }
}
