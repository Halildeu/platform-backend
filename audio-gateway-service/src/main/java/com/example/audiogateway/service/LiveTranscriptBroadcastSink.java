package com.example.audiogateway.service;

import com.example.audiogateway.dto.TranscriptResult;

/**
 * Decorator {@link DirectSttTranscriptResultSink} that first forwards to the
 * durable sink (Redis stream), then fans the same {@code TranscriptResult}
 * out to {@link LiveTranscriptStreamHub} so SSE subscribers (web viewers)
 * receive the same event the recording desktop just observed.
 *
 * <p>Order is identical to {@link LiveAnalyzeTriggerSink} — durable first,
 * best-effort broadcast second. A slow or broken hub MUST NOT stall the
 * durable emission.
 *
 * <p>The broadcast is ephemeral: no persistence, no replay. Late web
 * subscribers only see events after they connected. Canonical transcript
 * remains the persistence system's job (meeting-service).
 */
public final class LiveTranscriptBroadcastSink implements DirectSttTranscriptResultSink {

    private final DirectSttTranscriptResultSink delegate;
    private final LiveTranscriptStreamHub hub;

    public LiveTranscriptBroadcastSink(
            final DirectSttTranscriptResultSink delegate, final LiveTranscriptStreamHub hub) {
        this.delegate = delegate;
        this.hub = hub;
    }

    @Override
    public void emit(
            final TranscriptResult result, final DirectSttTranscriptResultContext context) {
        delegate.emit(result, context);
        try {
            hub.publish(context == null ? null : context.meetingId(), result);
        } catch (final RuntimeException ex) {
            // Broadcast is best-effort. Never mask the (already-committed)
            // durable emission with a relay error.
        }
    }
}
