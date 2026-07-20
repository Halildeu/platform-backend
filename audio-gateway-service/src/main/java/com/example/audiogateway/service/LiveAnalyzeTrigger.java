package com.example.audiogateway.service;

import com.example.audiogateway.dto.TranscriptResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Faz 24 İ4 — cadenced trigger of meeting-ai {@code /analyze/live} from
 * transcript results the direct-STT hop emits.
 *
 * <p>Aggregates transcript text per meeting; every {@code segmentWindow}
 * emissions the accumulator posts the joined transcript to meeting-ai
 * with a monotonically increasing {@code segment_seq}. The response is
 * ignored — meeting-ai fans the result out to SSE subscribers directly
 * (see platform-ai #270 {@code LiveStreamHub}).
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>Non-blocking failure isolation</b>: a broken relay (network,
 *       500, timeout) MUST NOT slow or fail the STT forwarding path.
 *       Errors are counted + logged (safe fields only) and swallowed.</li>
 *   <li><b>Sequence monotonicity</b>: {@code segment_seq} is per-meeting
 *       and strictly increasing so meeting-ai's version comparator can
 *       drop stale partials.</li>
 *   <li><b>PII discipline</b>: transcript TEXT is forwarded (that IS the
 *       payload) but never LOGGED. Metric labels are counts only.</li>
 * </ul>
 *
 * <p>Time-window fallback (a periodic flush independent of segment count)
 * is deferred to a follow-up slice; segment-count is the primary trigger
 * for the desktop viewer scope.
 */
public final class LiveAnalyzeTrigger {

    private static final Logger log = LoggerFactory.getLogger(LiveAnalyzeTrigger.class);

    private final WebClient webClient;
    private final int segmentWindow;
    private final String bearerToken;
    private final Duration timeout;
    private final Map<String, Aggregation> perMeeting = new ConcurrentHashMap<>();

    private final Counter publishAttempts;
    private final Counter publishSuccess;
    private final Counter publishError;
    private final Counter publishDropped;

    public LiveAnalyzeTrigger(
            final WebClient webClient,
            final int segmentWindow,
            final String bearerToken,
            final Duration timeout,
            final MeterRegistry meters) {
        if (segmentWindow < 1) {
            throw new IllegalArgumentException("segmentWindow must be >= 1");
        }
        this.webClient = webClient;
        this.segmentWindow = segmentWindow;
        this.bearerToken = bearerToken == null ? "" : bearerToken;
        this.timeout = timeout;
        this.publishAttempts = Counter.builder("audio_gw_live_analyze_publish_total")
                .description("Attempts to post to meeting-ai /analyze/live (per meeting-triggered flush)")
                .register(meters);
        this.publishSuccess = Counter.builder("audio_gw_live_analyze_publish_success_total")
                .description("Successful /analyze/live POSTs (2xx)")
                .register(meters);
        this.publishError = Counter.builder("audio_gw_live_analyze_publish_error_total")
                .description("Failed /analyze/live POSTs (non-2xx, timeout, connect error)")
                .register(meters);
        this.publishDropped = Counter.builder("audio_gw_live_analyze_drop_total")
                .description("Transcripts skipped (missing meetingId or blank text)")
                .register(meters);
    }

    /**
     * Feed a transcript result. If it advances the meeting's window past the
     * configured segment count, a live-analyze POST is triggered
     * asynchronously (fire-and-forget) and the aggregation is reset.
     *
     * <p>NEVER throws.
     */
    public void offer(final String meetingId, final TranscriptResult result) {
        if (meetingId == null || meetingId.isBlank()) {
            publishDropped.increment();
            return;
        }
        if (result == null || result.text() == null || result.text().isBlank()) {
            publishDropped.increment();
            return;
        }

        final Aggregation agg =
                perMeeting.computeIfAbsent(meetingId, k -> new Aggregation(segmentWindow));
        final Aggregation.Snapshot snapshot = agg.appendAndMaybeFlush(result.text().trim());
        if (snapshot == null) {
            return; // still accumulating
        }
        firePublish(meetingId, snapshot);
    }

    private void firePublish(final String meetingId, final Aggregation.Snapshot snapshot) {
        publishAttempts.increment();
        try {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("transcript", snapshot.transcript());
            body.put("meeting_id", meetingId);
            body.put("segment_seq", snapshot.segmentSeq());

            final WebClient.RequestBodySpec req = webClient
                    .post()
                    .uri("/analyze/live");
            if (!bearerToken.isEmpty()) {
                req.header("Authorization", "Bearer " + bearerToken);
            }
            req.bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(timeout)
                    .doOnSuccess(entity -> publishSuccess.increment())
                    .doOnError(err -> {
                        publishError.increment();
                        // PII discipline: log the failure class + status, NEVER the transcript.
                        log.warn(
                                "live-analyze trigger failed err_class={} meeting_id_len={} seq={}",
                                err.getClass().getSimpleName(),
                                meetingId.length(),
                                snapshot.segmentSeq());
                    })
                    .onErrorResume(err -> Mono.empty())
                    .subscribe();
        } catch (final RuntimeException ex) {
            publishError.increment();
            log.warn(
                    "live-analyze trigger dispatch failed err_class={} seq={}",
                    ex.getClass().getSimpleName(),
                    snapshot.segmentSeq());
        }
    }

    /**
     * Per-meeting append-and-flush accumulator. Buffers transcript
     * fragments; on each Nth append it exposes a snapshot for the caller
     * to publish, then resets. Segment seq monotonically increases across
     * the meeting's lifetime.
     */
    static final class Aggregation {
        private final int window;
        private final AtomicInteger seq = new AtomicInteger(0);
        // Guarded by `this`.
        private final List<String> pending = new java.util.ArrayList<>();

        Aggregation(final int window) {
            this.window = window;
        }

        synchronized Snapshot appendAndMaybeFlush(final String fragment) {
            pending.add(fragment);
            if (pending.size() < window) {
                return null;
            }
            final String joined = String.join(" ", pending);
            pending.clear();
            final int nextSeq = seq.incrementAndGet();
            return new Snapshot(joined, nextSeq);
        }

        record Snapshot(String transcript, int segmentSeq) {}
    }
}
