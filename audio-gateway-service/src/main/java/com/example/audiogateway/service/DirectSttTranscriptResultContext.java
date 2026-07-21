package com.example.audiogateway.service;

import java.util.List;

/**
 * Metadata that binds a live-stt transcript result back to the accepted audio chunk.
 *
 * <p>The context intentionally carries only gateway-derived ids and hash/size metadata.
 * It never carries raw audio bytes, bearer tokens, or request headers.
 *
 * <p>{@link #assembly()} is {@code null} for a raw committed chunk and set only on the
 * synthetic result {@link SentenceAssemblingSink} emits for an assembled line.
 *
 * <p>{@link #transport()} names which leg produced the result. It matters because the two
 * legs number their windows independently — each starts at 0 — so {@code windowSeq} is
 * only comparable WITHIN one transport. A session that falls back from the live socket to
 * REST would otherwise collide on {@code (sessionId, windowSeq)} and have real speech
 * rejected as a duplicate.
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
        Transport transport,
        Assembly assembly
) {

    /**
     * Which leg carried the audio. Window sequences restart per transport, so this is the
     * epoch boundary a downstream reorder buffer must reset on.
     */
    public enum Transport {
        /** Gateway-to-live-stt WebSocket bridge. */
        WEBSOCKET,
        /** Per-chunk REST {@code /transcribe} forward — the fallback path. */
        REST
    }

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
            final int byteLength,
            final Transport transport) {
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
                transport,
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
                transport,
                new Assembly(utterance.flushReason(), utterance.sourceEventIds()));
    }
}
