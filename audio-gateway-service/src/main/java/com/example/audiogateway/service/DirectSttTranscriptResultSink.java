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

    /**
     * Hand the result downstream.
     *
     * @return the durable event id this result was stored under, or {@code null} when this
     *     sink does not assign one. {@code null} means "unknown", never "failed" — a failed
     *     durable handoff still throws, as before.
     *     <p>The id is returned rather than discarded because it is the key the assembler's
     *     {@code sourceEventIds} audit trail is written in. Without it a live viewer receives
     *     the trail but nothing to match it against, and cannot tell which lines on screen an
     *     assembled utterance replaced.
     */
    String emit(TranscriptResult result, DirectSttTranscriptResultContext context);

    static DirectSttTranscriptResultSink noop() {
        return (result, context) -> null;
    }
}
