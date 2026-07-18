package com.example.audiogateway.service;

import com.example.audiogateway.dto.TranscriptResult;

/**
 * Downstream handoff for a parsed direct-STT transcript result.
 *
 * <p>Implementations must not log transcript text or segments. They must throw when the
 * durable handoff has no positive acknowledgement. Failures stay in the HTTP/WS terminal
 * chain, are metered, and cannot be reported to the client as a persisted transcript.
 */
@FunctionalInterface
public interface DirectSttTranscriptResultSink {

    void emit(TranscriptResult result, DirectSttTranscriptResultContext context);

    static DirectSttTranscriptResultSink noop() {
        return (result, context) -> {
        };
    }
}
