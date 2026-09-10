package com.example.audiogateway.service;

import com.example.audiogateway.dto.TranscriptResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;

/**
 * Faz 24 İ4 — cadenced trigger of meeting-ai {@code /analyze/live} from
 * transcript results the direct-STT hop emits.
 *
 * <p>Aggregates transcript text per meeting; every {@code segmentWindow}
 * emissions the accumulator offers a cumulative snapshot to meeting-ai.
 * One request per meeting may be active; while busy or within the cadence
 * interval, only the latest cumulative snapshot is retained. Responses are
 * relayed to the existing live hub with monotonically increasing versions.
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
public final class LiveAnalyzeTrigger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveAnalyzeTrigger.class);

    private final WebClient webClient;
    private final int segmentWindow;
    private final String bearerToken;
    private final Duration timeout;
    private final long minIntervalNanos;
    private volatile boolean closed;
    /** Optional live relay (Faz 24 İ5); null when broadcast is disabled. */
    private final LiveAnalysisStreamHub analysisHub;

    private final Map<String, Aggregation> perMeeting = new ConcurrentHashMap<>();

    private final Counter publishAttempts;
    private final Counter publishSuccess;
    private final Counter publishError;
    private final Counter publishDropped;
    private final Counter publishCoalesced;

    public LiveAnalyzeTrigger(
            final WebClient webClient,
            final int segmentWindow,
            final String bearerToken,
            final Duration timeout,
            final MeterRegistry meters) {
        this(webClient, segmentWindow, bearerToken, timeout, meters, null);
    }

    /**
     * @param analysisHub optional live relay; when non-null every successful
     *     analysis body is broadcast to that meeting's SSE subscribers so the
     *     decisions/actions appear DURING the meeting. Pass null to keep the
     *     pre-İ5 fire-and-forget behaviour.
     */
    public LiveAnalyzeTrigger(
            final WebClient webClient,
            final int segmentWindow,
            final String bearerToken,
            final Duration timeout,
            final MeterRegistry meters,
            final LiveAnalysisStreamHub analysisHub) {
        this(webClient, segmentWindow, bearerToken, timeout, meters, analysisHub, Duration.ZERO);
    }

    public LiveAnalyzeTrigger(
            final WebClient webClient,
            final int segmentWindow,
            final String bearerToken,
            final Duration timeout,
            final MeterRegistry meters,
            final LiveAnalysisStreamHub analysisHub,
            final Duration minInterval) {
        if (segmentWindow < 1) {
            throw new IllegalArgumentException("segmentWindow must be >= 1");
        }
        this.webClient = webClient;
        this.segmentWindow = segmentWindow;
        this.bearerToken = bearerToken == null ? "" : bearerToken;
        this.timeout = timeout;
        if (minInterval.isNegative()) {
            throw new IllegalArgumentException("minInterval must not be negative");
        }
        this.minIntervalNanos = minInterval.toNanos();
        this.analysisHub = analysisHub;
        this.publishAttempts = Counter.builder("audio_gw_live_analyze_publish_total")
                .description("Attempts to post to meeting-ai /analyze/live (per meeting-triggered flush)")
                .register(meters);
        this.publishCoalesced = Counter.builder("audio_gw_live_analyze_coalesced_total")
                .description("Pending cumulative snapshots replaced by a newer snapshot")
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
     * asynchronously. New cumulative windows coalesce while a request is pending.
     *
     * <p>NEVER throws.
     */
    public void offer(final String meetingId, final TranscriptResult result) {
        if (closed) return;
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
        synchronized (agg) {
            // Snapshot sequence is assigned under the aggregation lock. A slower
            // producer must not replace newer pending context with an older one.
            if (snapshot.segmentSeq() <= agg.lastQueuedSequence) return;
            if (agg.pendingSnapshot != null) publishCoalesced.increment();
            agg.lastQueuedSequence = snapshot.segmentSeq();
            agg.pendingSnapshot = snapshot;
        }
        scheduleNext(meetingId, agg);
    }

    private void scheduleNext(final String meetingId, final Aggregation agg) {
        final long delayNanos;
        final Disposable.Swap subscription;
        synchronized (agg) {
            if (closed || agg.publishing || agg.pendingSnapshot == null) return;
            agg.publishing = true;
            delayNanos = agg.hasPublished
                    ? Math.max(0, minIntervalNanos - (System.nanoTime() - agg.lastPublishNanos)) : 0;
            subscription = Disposables.swap();
            agg.subscription = subscription;
        }
        final Mono<Long> cadence = delayNanos == 0
                ? Mono.just(0L) : Mono.delay(Duration.ofNanos(delayNanos));
        subscription.update(cadence.flatMap(ignored -> Mono.defer(() -> {
            final Aggregation.Snapshot snapshot;
            synchronized (agg) {
                if (closed) return Mono.empty();
                snapshot = agg.pendingSnapshot;
                agg.pendingSnapshot = null;
                agg.hasPublished = true;
                agg.lastPublishNanos = System.nanoTime();
            }
            return firePublish(meetingId, snapshot);
        })).doFinally(signal -> {
            synchronized (agg) {
                agg.publishing = false;
            }
            scheduleNext(meetingId, agg);
        }).subscribe());
    }

    private Mono<Void> firePublish(final String meetingId, final Aggregation.Snapshot snapshot) {
        return Mono.defer(() -> {
            publishAttempts.increment();
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
            return req.bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .doOnSuccess(
                            analysisJson -> {
                                publishSuccess.increment();
                                // Relay to live viewers. Never let a hub failure
                                // turn a successful analysis into a failed one.
                                if (analysisHub != null) {
                                    analysisHub.publish(meetingId, analysisJson);
                                }
                            })
                    .then();
        }).doOnError(err -> {
                        publishError.increment();
                        // PII discipline: log the failure class + status, NEVER the transcript.
                        log.warn(
                                "live-analyze trigger failed err_class={} meeting_id_len={} seq={}",
                                err.getClass().getSimpleName(),
                                meetingId.length(),
                                snapshot.segmentSeq());
                    })
                    .onErrorResume(err -> Mono.empty());
    }

    @Override
    public void close() {
        closed = true;
        perMeeting.values().forEach(agg -> {
            synchronized (agg) {
                if (agg.subscription != null) agg.subscription.dispose();
                agg.pendingSnapshot = null;
                agg.history.setLength(0);
            }
        });
        perMeeting.clear();
    }

    /**
     * Per-meeting cumulative accumulator. Buffers transcript fragments; on
     * each Nth append it exposes a snapshot of everything heard so far (size
     * bounded) for the caller to publish. Segment seq monotonically increases
     * across the meeting's lifetime.
     */
    static final class Aggregation {
        /**
         * Upper bound on the cumulative transcript handed to meeting-ai per flush.
         * meeting-ai rejects bodies above its {@code max_transcript_chars}
         * (100k default); we keep a comfortable margin and drop the OLDEST text
         * first, so a long meeting still analyses its recent hour rather than
         * failing outright.
         */
        static final int MAX_CUMULATIVE_CHARS = 60_000;

        private final int window;
        private final AtomicInteger seq = new AtomicInteger(0);
        // Guarded by `this`.
        private int sinceFlush = 0;
        private final StringBuilder history = new StringBuilder();
        private Snapshot pendingSnapshot;
        private int lastQueuedSequence;
        private boolean publishing;
        private boolean hasPublished;
        private long lastPublishNanos;
        private Disposable.Swap subscription;

        Aggregation(final int window) {
            this.window = window;
        }

        /**
         * Appends a fragment; every {@code window} fragments exposes a snapshot of
         * the CUMULATIVE transcript so far (bounded by
         * {@link #MAX_CUMULATIVE_CHARS}, oldest text dropped).
         *
         * <p>Faz 24 Görevler dilim-4 / Zeynep 2026-08-31 finding 1: the previous
         * implementation reset the buffer on every flush, so each live analysis
         * only ever saw the last {@code window} fragments and the "live summary"
         * degraded to "the latest sentence". Live analysis is meant to answer
         * "what has been decided SO FAR", which needs the accumulated context.
         */
        synchronized Snapshot appendAndMaybeFlush(final String fragment) {
            if (history.length() > 0) {
                history.append(' ');
            }
            history.append(fragment);
            if (history.length() > MAX_CUMULATIVE_CHARS) {
                final int cut = history.length() - MAX_CUMULATIVE_CHARS;
                // Drop whole leading fragments: advance to the next space so the
                // retained text never starts mid-word.
                int boundary = history.indexOf(" ", cut);
                history.delete(0, boundary < 0 ? cut : boundary + 1);
            }
            sinceFlush++;
            if (sinceFlush < window) {
                return null;
            }
            sinceFlush = 0;
            final int nextSeq = seq.incrementAndGet();
            return new Snapshot(history.toString(), nextSeq);
        }

        record Snapshot(String transcript, int segmentSeq) {}
    }
}
