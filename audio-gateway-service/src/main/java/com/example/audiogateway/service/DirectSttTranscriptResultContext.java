package com.example.audiogateway.service;

/**
 * Metadata that binds a live-stt transcript result back to the accepted audio chunk.
 *
 * <p>The context intentionally carries only gateway-derived ids and hash/size metadata.
 * It never carries raw audio bytes, bearer tokens, or request headers.
 */
public record DirectSttTranscriptResultContext(
        String sessionId,
        Long tenantId,
        Long userId,
        long chunkSeq,
        long chunkStartedAtMs,
        String meetingId,
        String deviceId,
        String requestedLanguage,
        String audioFormat,
        int sampleRateHz,
        int channels,
        String correlationId,
        String sha256,
        int byteLength
) {
}
