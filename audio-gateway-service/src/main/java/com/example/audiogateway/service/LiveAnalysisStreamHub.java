package com.example.audiogateway.service;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Faz 24 İ5 — in-memory pub/sub of meeting-ai live-analysis payloads per
 * {@code meetingId}, so the recording desktop (and web viewers) can see
 * decisions and action items WHILE the meeting is still running.
 *
 * <h2>Why the gateway relays instead of clients calling meeting-ai</h2>
 *
 * <p>meeting-ai runs on the Denetim GPU host behind WireGuard and is only
 * reachable over a pinned-identity mutual-TLS bridge; it has no public route
 * and no tenant-aware authorisation of its own. The gateway already holds
 * both halves of what a viewer needs — it is the only component that talks
 * to meeting-ai (with the pod's client certificate), and it already
 * authenticates viewers and checks {@code meeting:{id}#can_view}. Relaying
 * the analysis body it ALREADY receives therefore adds live visibility
 * without exposing meeting-ai and without a second authorisation surface.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>Ephemeral</b> — no persistence, no replay buffer. The canonical
 *       analysis is meeting-service's (durable {@code /analyze} path); this
 *       is the live view only.</li>
 *   <li><b>Drop-oldest under back-pressure</b> — a stalled viewer can never
 *       slow the transcript path.</li>
 *   <li><b>Best-effort</b> — an emit failure MUST NOT propagate into the
 *       trigger, exactly like {@link LiveTranscriptStreamHub}.</li>
 *   <li><b>Owner-authorised</b> — the SSE controller enforces access before
 *       subscribing; the hub trusts its caller.</li>
 * </ul>
 *
 * <p>The payload is carried as the raw JSON string meeting-ai returned. The
 * gateway deliberately does NOT re-model it: the analysis schema
 * ({@code schema_version}, grounding fields, citations) is owned by
 * meeting-ai/ADR-0043, and a gateway-side mirror would silently drop fields
 * every time that contract grows.
 */
public class LiveAnalysisStreamHub {

    private static final Logger log = LoggerFactory.getLogger(LiveAnalysisStreamHub.class);

    /**
     * Ring buffer size per meeting. Analysis frames arrive on a segment-window
     * cadence (tens of seconds apart), so 32 frames is many minutes of stall
     * tolerance — well beyond any realistic UI hiccup.
     */
    static final int BUFFER_CAPACITY = 32;

    private final ConcurrentMap<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

    /**
     * Publish the analysis JSON {@code payload} to whoever is subscribed to
     * {@code meetingId}. If nobody is subscribed the call is a cheap no-op.
     * Never throws.
     */
    public void publish(final String meetingId, final String payload) {
        if (meetingId == null || meetingId.isBlank() || payload == null || payload.isBlank()) {
            return;
        }
        final Sinks.Many<String> sink = sinks.get(meetingId);
        if (sink == null) {
            // Nobody is watching — do NOT allocate a sink just to drop frames.
            return;
        }
        try {
            final Sinks.EmitResult emitResult = sink.tryEmitNext(payload);
            if (emitResult.isFailure()) {
                log.debug(
                        "live-analysis broadcast emit skipped meetingId={} reason={}",
                        meetingId,
                        emitResult);
            }
        } catch (final RuntimeException ex) {
            log.debug("live-analysis broadcast emit threw meetingId={}", meetingId, ex);
        }
    }

    /**
     * Subscribe to future analysis frames for {@code meetingId}. Live-only:
     * a late subscriber sees the next frame, not the previous ones.
     */
    public Flux<String> subscribe(final String meetingId) {
        Objects.requireNonNull(meetingId, "meetingId");
        final Sinks.Many<String> sink =
                sinks.computeIfAbsent(
                        meetingId,
                        k -> Sinks.many().multicast().onBackpressureBuffer(BUFFER_CAPACITY, false));
        return sink.asFlux()
                .doFinally(
                        signal -> {
                            if (sink.currentSubscriberCount() <= 1) {
                                // Last subscriber leaving — drop the sink so the
                                // map does not grow unbounded across meetings.
                                sinks.remove(meetingId, sink);
                            }
                        });
    }

    /** Test/observability helper. */
    int activeMeetings() {
        return sinks.size();
    }
}
