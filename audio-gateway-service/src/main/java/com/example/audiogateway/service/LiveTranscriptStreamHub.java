package com.example.audiogateway.service;

import com.example.audiogateway.dto.LiveTranscriptEvent;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * In-memory pub/sub of {@link LiveTranscriptEvent} entries per {@code meetingId}
 * so that clients other than the recording desktop can subscribe to live
 * transcript increments over SSE (the "desktop → web live view" seam).
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>Ephemeral</b> — no persistence. Late subscribers only see events
 *       emitted after they connect; they do NOT get a replay buffer. That is
 *       the persistence system's job (meeting-service canonical transcript).</li>
 *   <li><b>Drop-oldest under back-pressure</b> — a slow subscriber cannot
 *       stall the recorder path. If the multicast sink buffer overflows the
 *       oldest events are dropped so the recorder keeps flowing.</li>
 *   <li><b>Best-effort broadcast</b> — an emit failure MUST NOT propagate
 *       back to the durable sink chain. This mirrors {@link LiveAnalyzeTriggerSink}.</li>
 *   <li><b>Owner-authorised</b> — the SSE controller is what enforces
 *       {@code meeting:{id}#can_view} before a subscription; the hub trusts
 *       the caller has already authorised.</li>
 * </ul>
 *
 * <p>Sinks are lazily created per {@code meetingId} on first publish and
 * torn down when the last subscriber leaves (via {@code doFinally}). This
 * keeps the map bounded even under long uptimes.
 */
public class LiveTranscriptStreamHub {

    private static final Logger log = LoggerFactory.getLogger(LiveTranscriptStreamHub.class);

    /**
     * Ring buffer size per meeting. 128 frames covers a ~4 minute stall in a
     * subscriber even at 500 ms cadence — far more than any realistic UI hiccup;
     * beyond it we drop oldest, which is the right trade for a live viewer.
     */
    static final int BUFFER_CAPACITY = 128;

    private final ConcurrentMap<String, Sinks.Many<LiveTranscriptEvent>> sinks =
            new ConcurrentHashMap<>();

    /**
     * Publish {@code result} to whoever is subscribed to {@code meetingId}.
     * If nobody is subscribed the call is a cheap no-op. Never throws.
     */
    public void publish(final String meetingId, final LiveTranscriptEvent result) {
        if (meetingId == null || meetingId.isBlank() || result == null) {
            return;
        }
        final Sinks.Many<LiveTranscriptEvent> sink = sinks.get(meetingId);
        if (sink == null) {
            // Nobody is watching — do NOT allocate a sink just to drop events.
            return;
        }
        try {
            final Sinks.EmitResult emitResult = sink.tryEmitNext(result);
            if (emitResult.isFailure()) {
                log.debug(
                        "live-transcript broadcast emit skipped meetingId={} reason={}",
                        meetingId,
                        emitResult);
            }
        } catch (final RuntimeException ex) {
            log.debug("live-transcript broadcast emit threw meetingId={}", meetingId, ex);
        }
    }

    /**
     * Subscribe to future events for {@code meetingId}. The returned flux is
     * live-only (no replay); it completes when the meeting sink is torn down
     * by an operator restart, and is cancellable at the transport layer.
     */
    public Flux<LiveTranscriptEvent> subscribe(final String meetingId) {
        Objects.requireNonNull(meetingId, "meetingId");
        final Sinks.Many<LiveTranscriptEvent> sink =
                sinks.computeIfAbsent(
                        meetingId,
                        k ->
                                Sinks.many()
                                        .multicast()
                                        .onBackpressureBuffer(BUFFER_CAPACITY, false));
        return sink.asFlux()
                .doFinally(
                        signal -> {
                            if (sink.currentSubscriberCount() <= 1) {
                                // Last subscriber leaving — drop the sink so
                                // the map does not grow unbounded across meetings.
                                sinks.remove(meetingId, sink);
                            }
                        });
    }

    /** Test/observability helper. */
    int activeMeetings() {
        return sinks.size();
    }
}
