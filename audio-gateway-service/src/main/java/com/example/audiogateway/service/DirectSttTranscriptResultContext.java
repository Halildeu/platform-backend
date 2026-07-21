package com.example.audiogateway.service;

import java.util.List;

/**
 * Metadata that binds a live-stt transcript result back to the accepted audio chunk.
 *
 * <p>The context intentionally carries only gateway-derived ids and hash/size metadata.
 * It never carries raw audio bytes, bearer tokens, or request headers.
 *
 * <p>{@link #assembly()} is {@code null} for a raw committed chunk and set only on the
 * synthetic result {@link SentenceAssemblingSink} emits for an assembled line. Producers
 * of raw results use the shorter constructor and are unaffected.
 */
public record DirectSttTranscriptResultContext(
        String sessionId,
        Long tenantId,
        Long userId,
        long chunkSeq,
        long chunkStartedAtMs,
        long windowSeq,
        long firstChunkSeq,
        long lastChunkSeq,
        long windowStartedAtMs,
        long windowEndedAtMs,
        int audioDurationMs,
        String flushReason,
        String meetingId,
        String deviceId,
        String requestedLanguage,
        String audioFormat,
        int sampleRateHz,
        int channels,
        String correlationId,
        String sha256,
        int byteLength,
        Assembly assembly
) {

    /**
     * Provenance of an assembled transcript line.
     *
     * @param reason why the line closed — a {@code SentenceAssembler.REASON_*} value
     * @param sourceEventIds the committed events folded into the line, in order, so a
     *     rendered line can always be traced back to the raw events it came from
     */
    public record Assembly(String reason, List<String> sourceEventIds) {
        public Assembly {
            sourceEventIds = List.copyOf(sourceEventIds);
        }
    }

    /** A raw committed chunk — every producing call site in the forward path uses this. */
    public DirectSttTranscriptResultContext(
            final String sessionId,
            final Long tenantId,
            final Long userId,
            final long chunkSeq,
            final long chunkStartedAtMs,
            final long windowSeq,
            final long firstChunkSeq,
            final long lastChunkSeq,
            final long windowStartedAtMs,
            final long windowEndedAtMs,
            final int audioDurationMs,
            final String flushReason,
            final String meetingId,
            final String deviceId,
            final String requestedLanguage,
            final String audioFormat,
            final int sampleRateHz,
            final int channels,
            final String correlationId,
            final String sha256,
            final int byteLength) {
        this(
                sessionId,
                tenantId,
                userId,
                chunkSeq,
                chunkStartedAtMs,
                windowSeq,
                firstChunkSeq,
                lastChunkSeq,
                windowStartedAtMs,
                windowEndedAtMs,
                audioDurationMs,
                flushReason,
                meetingId,
                deviceId,
                requestedLanguage,
                audioFormat,
                sampleRateHz,
                channels,
                correlationId,
                sha256,
                byteLength,
                null);
    }

    /** This context re-stamped as the carrier of an assembled line. */
    public DirectSttTranscriptResultContext withAssembly(
            final AssembledUtterance utterance) {
        return new DirectSttTranscriptResultContext(
                sessionId,
                tenantId,
                userId,
                chunkSeq,
                chunkStartedAtMs,
                windowSeq,
                firstChunkSeq,
                lastChunkSeq,
                utterance.startedAtMs(),
                utterance.endedAtMs(),
                utterance.speechDurationMs(),
                flushReason,
                meetingId,
                deviceId,
                requestedLanguage,
                audioFormat,
                sampleRateHz,
                channels,
                correlationId,
                sha256,
                byteLength,
                new Assembly(utterance.flushReason(), utterance.sourceEventIds()));
    }
}
