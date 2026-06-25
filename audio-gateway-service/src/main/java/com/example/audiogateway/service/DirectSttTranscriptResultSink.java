package com.example.audiogateway.service;

import com.example.audiogateway.dto.TranscriptResult;

/**
 * Downstream handoff for a parsed direct-STT transcript result.
 *
 * <p>Implementations must not log transcript text or segments. Failures are
 * surfaced to {@link DirectSttForwardingDispatcher}, which records a metric while
 * keeping chunk admission unaffected.
 */
@FunctionalInterface
public interface DirectSttTranscriptResultSink {

    void emit(TranscriptResult result, DirectSttTranscriptResultContext context);

    static DirectSttTranscriptResultSink noop() {
        return (result, context) -> {
        };
    }
}
