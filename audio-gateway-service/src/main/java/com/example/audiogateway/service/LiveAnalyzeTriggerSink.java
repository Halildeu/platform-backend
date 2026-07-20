package com.example.audiogateway.service;

import com.example.audiogateway.dto.TranscriptResult;

/**
 * Decorator {@link DirectSttTranscriptResultSink} that first forwards to
 * the primary durable sink (Redis stream), then feeds the live-analyze
 * aggregator so meeting-ai can render partial analyses on the desktop
 * viewer without waiting for the final call.
 *
 * <p>The order is deliberate: durability first, best-effort live-analyze
 * second. A slow or broken live-analyze relay MUST NOT stall the durable
 * handoff (which is what surfaces the transcript to the caller as
 * "persisted").
 */
public final class LiveAnalyzeTriggerSink implements DirectSttTranscriptResultSink {

    private final DirectSttTranscriptResultSink delegate;
    private final LiveAnalyzeTrigger trigger;

    public LiveAnalyzeTriggerSink(
            final DirectSttTranscriptResultSink delegate, final LiveAnalyzeTrigger trigger) {
        this.delegate = delegate;
        this.trigger = trigger;
    }

    @Override
    public void emit(
            final TranscriptResult result, final DirectSttTranscriptResultContext context) {
        delegate.emit(result, context);
        try {
            trigger.offer(context == null ? null : context.meetingId(), result);
        } catch (final RuntimeException ex) {
            // Live-analyze is best-effort. Never mask the (already-committed)
            // durable emission with a relay error.
        }
    }
}
