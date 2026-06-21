package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.TranscriptResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Direct-STT forwarding dispatcher — Faz 24 issue #182 CORE (architecture "A").
 *
 * <p><b>Decision trail:</b> cross-AI architecture decision (Codex {@code 019eeb45} +
 * Claude AGREE): "A = direct HTTP POST gateway→live-stt {@code /transcribe}, NOW;
 * Redis stays metadata/coordination-only; raw audio NEVER persisted." Implementation
 * contract hardened by Codex {@code 019eeb5f} REVISE absorb (bounded in-flight + hard
 * async boundary + drop-on-saturation + strict config validation).
 *
 * <p><b>Decorator, opt-in, NON-breaking.</b> Registered only when
 * {@code audio.gateway.direct-stt.enabled=true} (see
 * {@link com.example.audiogateway.config.DirectSttConfig}). It wraps whichever base
 * dispatcher {@code audio.gateway.dispatcher.mode} selects (Redis metadata producer or
 * NoOp) — so the existing path is preserved and a direct audio forward is layered on
 * top. With the flag off, this bean does not exist and behaviour is unchanged.
 *
 * <p><b>Concurrency contract (THE critical constraint).</b> {@link #dispatch} is invoked
 * inside {@link InMemoryAudioSessionRegistry#admitChunk}'s {@code synchronized} monitor.
 * Every synchronous instruction here runs on the global admission monitor, so this method
 * does the MINIMUM and crosses a hard async boundary before any HTTP/multipart work:
 * <ol>
 *   <li>{@code delegate.dispatch(cmd)} — respect the base backpressure/atomicity gate;
 *       if it is not {@code Accepted}, return it as-is and do NOT forward audio for a
 *       chunk we are rejecting.</li>
 *   <li>{@code inFlight.tryAcquire()} (non-blocking) — on saturation: PII-safe warn +
 *       {@code dropped_saturation} counter, return {@code Accepted} WITHOUT forwarding
 *       (best-effort; admission unaffected).</li>
 *   <li>Copy the raw audio out of the request-scoped read-only {@link ByteBuffer} into a
 *       fresh {@code byte[]} (absolute reads; buffer position untouched). The buffer must
 *       not be referenced after the chunk lifecycle.</li>
 *   <li>Schedule {@code forward(task)} onto a DEDICATED bounded {@link Scheduler}. The
 *       WebClient request (multipart assembly, {@code .post().body(...)}) is built INSIDE
 *       {@code forward}, never on the monitor.</li>
 *   <li>Return {@code Accepted} immediately — never await the HTTP response.</li>
 * </ol>
 *
 * <p><b>Failure semantics.</b> The forward is fire-and-forget best-effort: once the
 * delegate returns {@code Accepted}, an STT error / timeout / saturation can NOT change
 * the admission outcome (the chunk is already accepted). This means accepted metadata may
 * exist without a transcript; that gap is made visible via metrics + PII-safe logs.
 *
 * <p><b>PII boundary (ADR-0030).</b> Raw audio bytes cross the wire to live-stt (the point
 * of path A) but are NEVER persisted, NEVER sent to Redis, and NEVER logged — they live
 * only transiently in the copied {@code byte[]} and the in-flight HTTP body. Transcript
 * {@code text}/{@code segments} are parsed but NEVER logged. Logs/metrics carry only
 * non-content metadata: {@code sha256} prefix(8), length, correlationId, sessionId,
 * chunkSeq, status, elapsedMs.
 *
 * <p><b>Ordering.</b> Forwards complete out of order at live-stt. {@code /transcribe} is
 * per-chunk stateless, so ordering does not matter for THIS seam; the downstream transcript
 * assembler (issue #182 follow-up) reconciles by {@code chunkSeq}/{@code chunkStartedAtMs}.
 */
public class DirectSttForwardingDispatcher
        implements AudioChunkDispatcher, org.springframework.beans.factory.DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DirectSttForwardingDispatcher.class);

    private static final String METRIC_PREFIX = "audio_gateway_direct_stt_";
    private static final String AUDIO_PART = "audio";
    private static final String AUDIO_FILENAME = "chunk.bin";

    private final AudioChunkDispatcher delegate;
    private final WebClient webClient;
    private final AudioGatewayProperties.DirectStt cfg;
    private final MeterRegistry meters;
    private final Semaphore inFlight;
    private final Scheduler forwardScheduler;
    private final String transcribeUri;

    public DirectSttForwardingDispatcher(
            final AudioChunkDispatcher delegate,
            final WebClient webClient,
            final AudioGatewayProperties properties,
            final MeterRegistry meters) {
        this.delegate = delegate;
        this.webClient = webClient;
        this.cfg = properties.getDirectStt();
        this.meters = meters;
        this.inFlight = new Semaphore(cfg.getMaxInFlight());
        this.transcribeUri = cfg.getTranscribeUrl().trim();
        // Dedicated bounded scheduler — the "leave the admission monitor now" boundary.
        // Bounded so a slow/down live-stt cannot spawn unbounded threads; the Semaphore is
        // the real in-flight cap, this just hands work off the monitor thread.
        this.forwardScheduler = Schedulers.newBoundedElastic(
                Math.max(1, cfg.getMaxInFlight()),
                Integer.MAX_VALUE,
                "direct-stt-forward");
        // In-flight gauge from the semaphore (used permits = configured - available).
        meters.gauge(METRIC_PREFIX + "in_flight", this,
                d -> (double) (cfg.getMaxInFlight() - d.inFlight.availablePermits()));
    }

    @Override
    public DispatchOutcome dispatch(final ChunkDispatchCommand cmd) {
        // (1) Base dispatcher first — preserve its backpressure + atomicity gate.
        final DispatchOutcome base = delegate.dispatch(cmd);
        if (!(base instanceof DispatchOutcome.Accepted)) {
            return base;
        }

        // (2) Bounded in-flight — non-blocking acquire on the admission monitor.
        if (!inFlight.tryAcquire()) {
            counter("dropped_saturation").increment();
            log.warn("Direct-STT forward dropped (in-flight saturated, maxInFlight={}) {}",
                    cfg.getMaxInFlight(), kv(cmd));
            return base; // best-effort: admission unaffected
        }

        // (3) Copy raw audio off the request-scoped read-only buffer BEFORE the async hop.
        final byte[] audio;
        try {
            audio = copyBytes(cmd.payload().bytes(), cmd.payload().length());
        } catch (final RuntimeException ex) {
            inFlight.release();
            counter("exception").increment();
            log.warn("Direct-STT byte copy failed err={} {}", ex.getClass().getSimpleName(), kv(cmd));
            return base; // never break admission
        }

        // (4) Hand off the monitor immediately; build the request inside forward().
        final ForwardTask task = new ForwardTask(
                audio, cmd.sessionId(), cmd.chunkSeq(), cmd.meetingId(), cmd.deviceId(),
                cmd.language(), cmd.correlationId(), cmd.payload().sha256(), cmd.payload().length());
        try {
            forwardScheduler.schedule(() -> forward(task));
        } catch (final RuntimeException ex) {
            // Scheduler rejected (e.g. shutting down) — release permit, do not break admission.
            inFlight.release();
            counter("exception").increment();
            log.warn("Direct-STT schedule rejected err={} {}", ex.getClass().getSimpleName(), kv(cmd));
        }

        // (5) Return immediately — never await the HTTP response.
        return base;
    }

    /** Dispose the dedicated scheduler on context shutdown (graceful resource hygiene). */
    @Override
    public void destroy() {
        forwardScheduler.dispose();
    }

    /**
     * Async forward — runs OFF the admission monitor on {@link #forwardScheduler}. Builds the
     * multipart request, sends it, handles the response/error/timeout, and releases the
     * in-flight permit exactly once in every terminal path.
     */
    void forward(final ForwardTask task) {
        counter("attempted").increment();
        final AtomicBoolean released = new AtomicBoolean(false);

        try {
            final MultipartBodyBuilder body = new MultipartBodyBuilder();
            body.part(AUDIO_PART, new NamedByteArrayResource(task.audio(), AUDIO_FILENAME))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);

            final String uri = UriComponentsBuilder.fromUriString(transcribeUri)
                    .queryParam("meeting_id", nullSafe(task.meetingId()))
                    .queryParam("session_id", nullSafe(task.sessionId()))
                    .queryParam("device_id", nullSafe(task.deviceId()))
                    .queryParam("language", nullSafe(task.language()))
                    .build()
                    .toUriString();

            webClient.post()
                    .uri(uri)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve()
                    .bodyToMono(TranscriptResult.class)
                    .timeout(Duration.ofMillis(cfg.getResponseTimeoutMs()))
                    .doFinally(signal -> releaseOnce(released))
                    .subscribe(
                            result -> onSuccess(result, task),
                            error -> onError(error, task));
        } catch (final RuntimeException ex) {
            // Synchronous build/subscribe failure — release here (doFinally never ran).
            releaseOnce(released);
            counter("exception").increment();
            log.warn("Direct-STT forward setup failed err={} {}",
                    ex.getClass().getSimpleName(), kv(task));
        }
    }

    private void onSuccess(final TranscriptResult result, final ForwardTask task) {
        counter("success").increment();
        // PII-safe: metadata + sizes ONLY — never transcript text or segments.
        log.info("Direct-STT transcript received {} textLen={} sttLang={} elapsedMs={} model={}",
                kv(task),
                result == null ? 0 : result.textLength(),
                result == null ? null : result.language(),
                result == null ? null : result.elapsedMs(),
                result == null ? null : result.model());
        routeTranscript(result, task);
    }

    private void onError(final Throwable error, final ForwardTask task) {
        // Order matters: timeout → real HTTP status (4xx/5xx) → connection/transport → other.
        if (error instanceof java.util.concurrent.TimeoutException
                || causeContains(error, "TimeoutException")) {
            counter("timeout").increment();
            log.warn("Direct-STT forward timeout (responseTimeoutMs={}) {}",
                    cfg.getResponseTimeoutMs(), kv(task));
            return;
        }
        // Only a genuine 4xx/5xx is an HTTP error. WebClient can surface an interrupted/aborted
        // response (e.g. peer closed mid-200) as a WebClientResponseException carrying a 2xx
        // status — classify that as a connection error, not a server HTTP error, so the metric
        // and log stay honest.
        if (error instanceof WebClientResponseException wcEx && wcEx.getStatusCode().isError()) {
            final int status = wcEx.getStatusCode().value();
            counter("http_error", "status_family", statusFamily(status)).increment();
            log.warn("Direct-STT forward HTTP error status={} {}", status, kv(task));
            return;
        }
        if (error instanceof reactor.netty.http.client.PrematureCloseException
                || causeContains(error, "PrematureCloseException")
                || causeContains(error, "ConnectException")
                || causeContains(error, "ConnectTimeoutException")
                || causeContains(error, "ClosedChannelException")
                || causeContains(error, "ConnectionResetException")) {
            counter("connection_error").increment();
            log.warn("Direct-STT forward connection error err={} {}",
                    error.getClass().getSimpleName(), kv(task));
            return;
        }
        counter("exception").increment();
        log.warn("Direct-STT forward failed err={} {}", error.getClass().getSimpleName(), kv(task));
    }

    /**
     * TODO(#182 follow-up): route the parsed transcript to meeting-service /
     * transcript-service (e.g. transcript stream / per-session append) for assembly,
     * reconciling by {@code chunkSeq}/{@code chunkStartedAtMs}. This CORE seam intentionally
     * does NOT fake routing — it only confirms a PII-safe successful parse and leaves the
     * wiring to the follow-up slice. {@code result.text()} / {@code result.segments()} carry
     * transcript content and must be handed off WITHOUT logging.
     */
    private void routeTranscript(final TranscriptResult result, final ForwardTask task) {
        // no-op seam (see Javadoc) — success already metered + PII-safe logged in onSuccess.
    }

    private void releaseOnce(final AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            inFlight.release();
        }
    }

    /** Absolute-read copy — leaves the source buffer's position/limit untouched. */
    private static byte[] copyBytes(final ByteBuffer src, final int length) {
        final ByteBuffer dup = src.duplicate();
        final int n = Math.min(length, dup.remaining());
        final byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = dup.get(i);
        }
        return out;
    }

    private Counter counter(final String name) {
        return meters.counter(METRIC_PREFIX + name);
    }

    private Counter counter(final String name, final String tagKey, final String tagValue) {
        return meters.counter(METRIC_PREFIX + name, Tags.of(tagKey, tagValue));
    }

    private static String statusFamily(final int status) {
        return (status / 100) + "xx";
    }

    private static boolean causeContains(final Throwable ex, final String simpleName) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().contains(simpleName)) {
                return true;
            }
        }
        return false;
    }

    private static String kv(final ChunkDispatchCommand cmd) {
        return "sessionId=" + cmd.sessionId()
                + " chunkSeq=" + cmd.chunkSeq()
                + " correlationId=" + nullSafe(cmd.correlationId())
                + " sha256=" + shaPrefix(cmd.payload().sha256())
                + " length=" + cmd.payload().length();
    }

    private static String kv(final ForwardTask task) {
        return "sessionId=" + task.sessionId()
                + " chunkSeq=" + task.chunkSeq()
                + " correlationId=" + nullSafe(task.correlationId())
                + " sha256=" + shaPrefix(task.sha256())
                + " length=" + task.length();
    }

    /** First 8 hex chars only — NEVER the full hash in user-facing surfaces (PII boundary). */
    private static String shaPrefix(final String sha256) {
        if (sha256 == null) {
            return "";
        }
        return sha256.length() <= 8 ? sha256 : sha256.substring(0, 8);
    }

    private static String nullSafe(final String value) {
        return value == null ? "" : value;
    }

    /**
     * Immutable forwarding task captured under the monitor — carries the COPIED audio plus
     * routing metadata, so {@code forward()} has no reference to request-scoped state.
     */
    record ForwardTask(
            byte[] audio,
            String sessionId,
            long chunkSeq,
            String meetingId,
            String deviceId,
            String language,
            String correlationId,
            String sha256,
            int length) {
    }

    /**
     * {@link org.springframework.core.io.ByteArrayResource} with a filename so the multipart
     * {@code audio} part is sent as a file part (Content-Disposition filename), matching the
     * live-stt {@code /transcribe} multipart contract.
     */
    private static final class NamedByteArrayResource
            extends org.springframework.core.io.ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(final byte[] bytes, final String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
