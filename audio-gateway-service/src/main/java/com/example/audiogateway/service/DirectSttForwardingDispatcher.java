package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.TranscriptResult;
import com.example.audiogateway.service.AudioGatewayAuditSink.AuditEvent;
import com.example.audiogateway.service.AudioChunkDispatcher.SessionDiscardCommand;

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
import reactor.util.retry.Retry;

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
 * However, raw audio is NOT sent to the compute plane unless the
 * {@link AuditEvent.ChunkForwardedToComputePlane} event is successfully emitted first.
 * Audit failure blocks only the direct-STT forward, not chunk admission.
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

    private final AudioChunkDispatcher delegate;
    private final AudioGatewayAuditSink auditSink;
    private final DirectSttTranscriptResultSink transcriptResultSink;
    private final WebClient webClient;
    private final AudioGatewayProperties.DirectStt cfg;
    private final MeterRegistry meters;
    private final Semaphore inFlight;
    private final DirectSttAudioWindowAggregator aggregator;
    /** The #428 audio bound: how much PCM this process still owes a terminal path, per session. */
    private final DirectSttAudioAccountant accountant;
    /** Retry-After seconds for the two audio-bound refusals. Shared with the Redis path's
     *  vocabulary on purpose: a client sees one 429/503 contract, whichever gate refused. */
    private final long queueFullRetryAfterSeconds;
    private final long unavailableRetryAfterSeconds;
    private final Scheduler forwardScheduler;
    private final Scheduler transcriptSinkScheduler;
    private final String transcribeUri;

    public DirectSttForwardingDispatcher(
            final AudioChunkDispatcher delegate,
            final AudioGatewayAuditSink auditSink,
            final DirectSttTranscriptResultSink transcriptResultSink,
            final WebClient webClient,
            final AudioGatewayProperties properties,
            final MeterRegistry meters) {
        this.delegate = delegate;
        this.auditSink = auditSink;
        this.transcriptResultSink = transcriptResultSink;
        this.webClient = webClient;
        this.cfg = properties.getDirectStt();
        this.meters = meters;
        this.inFlight = new Semaphore(cfg.getMaxInFlight());
        this.aggregator = new DirectSttAudioWindowAggregator(
                cfg.getAggregation().getWindowSeconds(),
                cfg.getAggregation().getMaxBufferedSessions());
        // #836 landed maxBufferedSeconds as config and validated it; this is the
        // enforcement site that config was always for.
        this.accountant = new DirectSttAudioAccountant(
                properties.getBounds().getMaxBufferedSeconds());
        this.queueFullRetryAfterSeconds = properties.getDispatcher().getQueueFullRetryAfterSeconds();
        this.unavailableRetryAfterSeconds = properties.getDispatcher().getUnavailableRetryAfterSeconds();
        this.transcribeUri = cfg.getTranscribeUrl().trim();
        // Dedicated bounded scheduler — the "leave the admission monitor now" boundary.
        // Bounded so a slow/down live-stt cannot spawn unbounded threads; the Semaphore is
        // the real in-flight cap, this just hands work off the monitor thread.
        this.forwardScheduler = Schedulers.newBoundedElastic(
                Math.max(1, cfg.getMaxInFlight()),
                Integer.MAX_VALUE,
                "direct-stt-forward");
        this.transcriptSinkScheduler = Schedulers.newBoundedElastic(
                Math.max(1, cfg.getMaxInFlight()),
                Integer.MAX_VALUE,
                "direct-stt-transcript-sink");
        // In-flight gauge from the semaphore (used permits = configured - available).
        meters.gauge(METRIC_PREFIX + "in_flight", this,
                d -> (double) (cfg.getMaxInFlight() - d.inFlight.availablePermits()));
        meters.gauge(METRIC_PREFIX + "aggregation_active_sessions", aggregator,
                DirectSttAudioWindowAggregator::activeSessions);
        // Low cardinality: totals only, never a per-session label.
        meters.gauge(METRIC_PREFIX + "audio_bound_reserved_frames", accountant,
                DirectSttAudioAccountant::totalReservedFrames);
        meters.gauge(METRIC_PREFIX + "audio_bound_active_sessions", accountant,
                a -> (double) a.activeSessions());
        meters.gauge(METRIC_PREFIX + "audio_bound_negative_invariant_total", accountant,
                DirectSttAudioAccountant::negativeInvariantBreaches);
        meters.gauge(METRIC_PREFIX + "aggregation_buffered_bytes", aggregator,
                DirectSttAudioWindowAggregator::bufferedBytes);
        // Runtime acceptance scrapes a zero baseline before the first chunk.
        // Register these counters eagerly so "no event yet" is observable as 0
        // instead of being indistinguishable from missing instrumentation.
        counter("aggregation_chunks_buffered");
        counter("aggregation_dropped_capacity");
        counter("aggregation_shutdown_discarded_sessions");
        counter("aggregation_shutdown_discarded_bytes");
        counter("aggregation_session_discarded");
        counter("aggregation_session_discarded_bytes");
    }

    @Override
    public DispatchOutcome dispatch(final ChunkDispatchCommand cmd) {
        // The audio bound (#428 / platform-ai#257) is charged BEFORE the delegate — this is
        // the ordering fix. The delegate in Redis mode is RedisStreamsAudioChunkDispatcher,
        // whose dispatch() performs the XADD; charging afterwards let a 429/503 refusal
        // return to the client while a Redis descriptor had ALREADY been written, and the
        // retry re-XADD'd the same chunk (orphan descriptor, XLEN/XPENDING inflation). The
        // registry has already made the replay/idempotency decision before dispatch() is
        // called, so a replayed chunk never reaches this line — reserving here is safe and a
        // refused chunk truly leaves no trace: the delegate is not invoked at all.
        final DirectSttAudioAccountant.ReserveOutcome reserved = accountant.reserve(
                cmd.tenantId(), cmd.sessionId(), cmd.audioFormat(),
                cmd.sampleRateHz(), cmd.channels(), cmd.payload().length());

        return switch (reserved) {
            case DirectSttAudioAccountant.ReserveOutcome.OverLimit over -> {
                // Measured refusal: the session is holding its bound. 429 + Retry-After;
                // the client retries once the in-flight windows drain. Delegate NOT called.
                counter("audio_bound_over_limit").increment();
                log.warn("Direct-STT audio bound reached; rejecting chunk reservedFrames={} "
                                + "requestedFrames={} limitFrames={} {}",
                        over.reservedFrames(), over.requestedFrames(), over.limitFrames(), kv(cmd));
                yield new DispatchOutcome.QueueFull(queueFullRetryAfterSeconds);
            }
            case DirectSttAudioAccountant.ReserveOutcome.Unmeterable unknown -> {
                // NOT "there is room". Admitting audio whose duration we cannot derive is
                // how an unbounded buffer grows while the gauge reads zero, so this is an
                // explicit 503 rather than a silent pass. Delegate NOT called (no XADD).
                counter("audio_capacity_unknown", "reason", unknownReasonLabel(cmd)).increment();
                log.warn("Direct-STT capacity unmeterable ({}); rejecting chunk {}",
                        unknown.reason(), kv(cmd));
                yield new DispatchOutcome.Unavailable(unavailableRetryAfterSeconds);
            }
            case DirectSttAudioAccountant.ReserveOutcome.Reserved ok -> {
                // Reservation held. Now the base gate (Redis XADD / NoOp).
                final DispatchOutcome base;
                try {
                    base = delegate.dispatch(cmd);
                } catch (final RuntimeException ex) {
                    // A delegate should normally return Unavailable, but an unexpected
                    // fail-loud exception must not strand the charge before it propagates.
                    refund(cmd, ok.frames());
                    throw ex;
                }
                if (!(base instanceof DispatchOutcome.Accepted)) {
                    // The base gate refused (QueueFull/Unavailable). No bytes are queued for
                    // a chunk it rejected, so the reservation must be released terminally —
                    // otherwise a base-side backpressure refusal would leak capacity.
                    refund(cmd, ok.frames());
                    yield base;
                }
                dispatchReserved(cmd, ok.frames());
                yield base;
            }
        };
    }

    /** The chunk is paid for; move its bytes into the aggregator or straight to a forward. */
    private void dispatchReserved(final ChunkDispatchCommand cmd, final long chunkFrames) {
        final byte[] audio;
        try {
            audio = copyBytes(cmd.payload().bytes(), cmd.payload().length());
        } catch (final RuntimeException ex) {
            // The bytes never made it in, so the charge must not stand.
            refund(cmd, chunkFrames);
            counter("exception").increment();
            log.warn("Direct-STT byte copy failed err={} {}", ex.getClass().getSimpleName(), kv(cmd));
            return;
        }

        if (cfg.getAggregation().isEnabled()) {
            // The aggregator now owns these frames; each window it emits refunds its own.
            appendAggregated(cmd, audio, chunkFrames);
        } else {
            // No aggregation: the chunk IS the unit that leaves, so it carries its own refund.
            scheduleForward(chunkTask(cmd, audio, chunkFrames));
        }
    }

    /** Low-cardinality reason label — never the session id, never the raw byte length. */
    private static String unknownReasonLabel(final ChunkDispatchCommand cmd) {
        if (cmd.audioFormat() != AudioFormat.PCM16) {
            return "non_pcm16_format";
        }
        if (cmd.sampleRateHz() <= 0 || cmd.channels() <= 0) {
            return "invalid_pcm_params";
        }
        return "partial_frame";
    }

    private void refund(final ChunkDispatchCommand cmd, final long frames) {
        accountant.refundHandle(cmd.tenantId(), cmd.sessionId(), frames).release();
    }

    @Override
    public void finishSession(final SessionFinishCommand cmd) {
        if (cfg.getAggregation().isEnabled()) {
            aggregator.finish(cmd).ifPresent(window -> {
                counter("aggregation_windows_flushed", "reason", "finish").increment();
                recordWindowMetrics(window);
                scheduleForward(windowTask(window, "finish"));
            });
        }
        delegate.finishSession(cmd);
    }

    @Override
    public void discardSession(final SessionDiscardCommand cmd) {
        if (cfg.getAggregation().isEnabled()) {
            switch (aggregator.discard(cmd)) {
                case DirectSttAudioWindowAggregator.DiscardOutcome.Discarded discarded -> {
                    accountant.refundHandle(
                            cmd.tenantId(), cmd.sessionId(), discarded.frames()).release();
                    counter("aggregation_session_discarded").increment();
                    counter("aggregation_session_discarded_bytes").increment(discarded.bytes());
                    log.info("Direct-STT session tail discarded on server terminal sessionId={} "
                                    + "correlationId={} bytes={}",
                            cmd.sessionId(), nullSafe(cmd.correlationId()), discarded.bytes());
                }
                case DirectSttAudioWindowAggregator.DiscardOutcome.NotFound ignored -> {
                    // Idempotent retry or a session whose full window already left the aggregator.
                }
                case DirectSttAudioWindowAggregator.DiscardOutcome.OwnerMismatch ignored ->
                        throw new IllegalStateException(
                                "Direct-STT session discard owner mismatch for " + cmd.sessionId());
            }
        }
        delegate.discardSession(cmd);
    }

    /** Dispose the dedicated scheduler on context shutdown (graceful resource hygiene). */
    @Override
    public void destroy() {
        final DirectSttAudioWindowAggregator.DiscardSummary discarded = aggregator.discardAll();
        // The buffered audio and its reservations go together, which is the whole reason a
        // process-local counter is safe here: nothing durable is left believing in bytes
        // that no longer exist.
        accountant.close();
        if (discarded.sessions() > 0) {
            counter("aggregation_shutdown_discarded_sessions").increment(discarded.sessions());
            counter("aggregation_shutdown_discarded_bytes").increment(discarded.bytes());
            log.warn("Direct-STT shutdown discarded buffered PCM tails sessions={} bytes={}; "
                            + "clients must finish sessions before rollout",
                    discarded.sessions(), discarded.bytes());
        }
        forwardScheduler.dispose();
        transcriptSinkScheduler.dispose();
    }

    private void appendAggregated(
            final ChunkDispatchCommand cmd, final byte[] audio, final long chunkFrames) {
        try {
            final DirectSttAudioWindowAggregator.AppendResult result =
                    aggregator.append(cmd, audio);
            if (result.capacityExceeded()) {
                // The aggregator refused the bytes, so nothing is holding them: refund.
                refund(cmd, chunkFrames);
                counter("aggregation_dropped_capacity").increment();
                log.warn("Direct-STT aggregation capacity exceeded (maxBufferedSessions={}) {}",
                        cfg.getAggregation().getMaxBufferedSessions(), kv(cmd));
                return;
            }
            counter("aggregation_chunks_buffered").increment();
            result.windows().forEach(window -> {
                counter("aggregation_windows_flushed", "reason", "window_full").increment();
                recordWindowMetrics(window);
                scheduleForward(windowTask(window, "window_full"));
            });
        } catch (final RuntimeException ex) {
            // The append's outcome is unknown, so the safest accounting is to refund this
            // chunk: an over-refund would alarm loudly (negative invariant), whereas a
            // leaked charge would silently shrink the session's bound for the rest of its life.
            refund(cmd, chunkFrames);
            counter("aggregation_error").increment();
            log.warn("Direct-STT aggregation failed err={} {}",
                    ex.getClass().getSimpleName(), kv(cmd));
        } finally {
            java.util.Arrays.fill(audio, (byte) 0);
        }
    }

    private void scheduleForward(final ForwardTask task) {
        if (!inFlight.tryAcquire()) {
            // Saturation drop is a terminal path: these bytes are never going anywhere.
            task.refund().release();
            counter("dropped_saturation").increment();
            log.warn("Direct-STT forward dropped (in-flight saturated, maxInFlight={}) {}",
                    cfg.getMaxInFlight(), kv(task));
            clearAudio(task);
            return;
        }
        try {
            forwardScheduler.schedule(() -> forward(task));
        } catch (final RuntimeException ex) {
            inFlight.release();
            task.refund().release();
            clearAudio(task);
            counter("exception").increment();
            log.warn("Direct-STT schedule rejected err={} {}",
                    ex.getClass().getSimpleName(), kv(task));
        }
    }

    private ForwardTask chunkTask(
            final ChunkDispatchCommand cmd, final byte[] audio, final long chunkFrames) {
        final int durationMs = cmd.audioFormat() == AudioFormat.PCM16
                ? pcmDurationMs(audio.length, cmd.sampleRateHz(), cmd.channels())
                : 0;
        return new ForwardTask(
                audio, cmd.sessionId(), cmd.tenantId(), cmd.userId(),
                cmd.chunkSeq(), cmd.chunkSeq(), cmd.chunkSeq(),
                cmd.chunkStartedAtMs(), durationMs, "chunk",
                cmd.meetingId(), cmd.deviceId(), cmd.language(), cmd.audioFormat().name(),
                cmd.sampleRateHz(), cmd.channels(), cmd.correlationId(), cmd.payload().sha256(),
                cmd.payload().length(),
                accountant.refundHandle(cmd.tenantId(), cmd.sessionId(), chunkFrames));
    }

    /**
     * The task for a flushed window, carrying the refund for the frames IT holds.
     *
     * <p>Not the frames of any one chunk: the aggregator slices chunks at window
     * boundaries, so this window's audio may come from several chunks and a chunk's audio
     * may leave in several windows. Charges and refunds only agree in total — which is
     * also what makes the aggregator to queued/in-flight hand-off carry the same
     * reservation rather than count a second one.
     */
    private ForwardTask windowTask(
            final DirectSttAudioWindowAggregator.AudioWindow window,
            final String flushReason) {
        final long windowFrames = DirectSttAudioAccountant.framesIn(window.audio().length, window.channels());
        if (windowFrames < 0) {
            // framesIn returns -1 only for a non-whole-frame window — which cannot happen
            // today because reserve() rejects partial frames up front, so a window is always
            // built from whole-frame chunks. If it ever did, flooring the refund to 0 would
            // silently leak the charge (the reservation never comes back and the session's
            // bound shrinks for its whole life). Fail loud instead of hiding it.
            throw new IllegalStateException(
                    "Window has non-whole-frame audio (bytes=" + window.audio().length
                            + ", channels=" + window.channels() + ") — refund would be ambiguous");
        }
        return new ForwardTask(
                window.audio(), window.sessionId(), window.tenantId(), window.userId(),
                window.windowSeq(), window.firstChunkSeq(), window.lastChunkSeq(),
                window.startedAtMs(), window.durationMs(), flushReason,
                window.meetingId(), window.deviceId(), window.language(), AudioFormat.PCM16.name(),
                window.sampleRateHz(), window.channels(), window.correlationId(), window.sha256(),
                window.audio().length,
                accountant.refundHandle(window.tenantId(), window.sessionId(), windowFrames));
    }

    private void recordWindowMetrics(final DirectSttAudioWindowAggregator.AudioWindow window) {
        meters.summary(METRIC_PREFIX + "aggregation_window_bytes").record(window.audio().length);
        meters.summary(METRIC_PREFIX + "aggregation_window_duration_ms")
                .record(window.durationMs());
    }

    private static int pcmDurationMs(
            final int byteLength, final int sampleRateHz, final int channels) {
        final long bytesPerSecond = (long) sampleRateHz * channels * 2L;
        return bytesPerSecond <= 0 ? 0 : (int) ((byteLength * 1000L) / bytesPerSecond);
    }

    /**
     * Async forward — runs OFF the admission monitor on {@link #forwardScheduler}. Builds the
     * multipart request, sends it, handles the response/error/timeout, and releases the
     * in-flight permit exactly once in every terminal path.
     */
    void forward(final ForwardTask task) {
        final AtomicBoolean released = new AtomicBoolean(false);

        try {
            if (!emitComputePlaneAudit(task)) {
                // Audit refused, so the audio never leaves — a terminal path like any other.
                task.refund().release();
                clearAudio(task);
                releaseOnce(released);
                return;
            }
            counter("attempted").increment();

            final MultipartBodyBuilder body = new MultipartBodyBuilder();
            final AudioFormat audioFormat = AudioFormat.valueOf(task.audioFormat());
            final byte[] partBytes;
            final String partFilename;
            final MediaType partContentType;
            if (audioFormat == AudioFormat.PCM16) {
                // live-stt cannot decode headerless raw PCM — proven live: raw PCM is rejected
                // with HTTP 400 whether labelled application/octet-stream, audio/L16, or
                // audio/wav; only a real WAV container (audio/wav) returns 200. The recorder
                // uploads raw PCM16 (X-Audio-Format: PCM16), so wrap it into a self-describing
                // WAV container using the session's PCM params and forward as audio/wav.
                partBytes = WavEncoder.pcm16ToWav(task.audio(), task.sampleRateHz(), task.channels());
                partFilename = audioFilename(AudioFormat.WAV);
                partContentType = MediaType.parseMediaType(AudioFormat.WAV.mediaType());
            } else {
                partBytes = task.audio();
                partFilename = audioFilename(audioFormat);
                partContentType = MediaType.parseMediaType(audioFormat.mediaType());
            }
            body.part(AUDIO_PART, new NamedByteArrayResource(partBytes, partFilename))
                    .contentType(partContentType);

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
                    .flatMap(result -> persistTranscriptResult(result, task))
                    .publishOn(forwardScheduler)
                    // doFinally covers EVERY terminal signal — success, error, timeout and
                    // cancel — which is exactly the set the audio bound must refund on.
                    .doFinally(signal -> {
                        task.refund().release();
                        clearAudio(task);
                        releaseOnce(released);
                    })
                    .subscribe(
                            result -> onSuccess(result, task),
                            error -> onError(error, task));
        } catch (final RuntimeException ex) {
            // Synchronous build/subscribe failure — release here (doFinally never ran).
            task.refund().release();
            clearAudio(task);
            releaseOnce(released);
            counter("exception").increment();
            log.warn("Direct-STT forward setup failed err={} {}",
                    ex.getClass().getSimpleName(), kv(task));
        }
    }

    private boolean emitComputePlaneAudit(final ForwardTask task) {
        try {
            auditSink.emit(new AuditEvent.ChunkForwardedToComputePlane(
                    task.sessionId(),
                    task.tenantId(),
                    task.userId(),
                    task.meetingId(),
                    task.deviceId(),
                    task.language(),
                    task.lastChunkSeq(),
                    task.windowSeq(),
                    task.firstChunkSeq(),
                    task.lastChunkSeq(),
                    task.chunkStartedAtMs(),
                    task.chunkStartedAtMs() + task.audioDurationMs(),
                    task.audioDurationMs(),
                    task.flushReason(),
                    task.audioFormat(),
                    task.sampleRateHz(),
                    task.channels(),
                    task.sha256(),
                    task.length(),
                    task.correlationId(),
                    System.currentTimeMillis(),
                    "live-stt"));
            return true;
        } catch (final Exception ex) {
            counter("audit_blocked").increment();
            log.warn("Direct-STT forward blocked because compute-plane audit failed err={} {}",
                    ex.getClass().getSimpleName(), kv(task));
            return false;
        }
    }

    private void onSuccess(final TranscriptResult result, final ForwardTask task) {
        counter("success").increment();
        if (result != null && result.elapsedMs() != null && task.audioDurationMs() > 0) {
            meters.summary(METRIC_PREFIX + "real_time_factor")
                    .record(result.elapsedMs() / task.audioDurationMs());
        }
        // PII-safe: metadata + sizes ONLY — never transcript text or segments.
        log.info("Direct-STT transcript received {} textLen={} sttLang={} elapsedMs={} model={}",
                kv(task),
                result == null ? 0 : result.textLength(),
                result == null ? null : result.language(),
                result == null ? null : result.elapsedMs(),
                result == null ? null : result.model());
    }

    private void onError(final Throwable error, final ForwardTask task) {
        // Order matters: timeout → real HTTP status (4xx/5xx) → connection/transport → other.
        if (error instanceof TranscriptResultDeliveryException) {
            counter("transcript_delivery_failed").increment();
            log.warn("Direct-STT transcript delivery failed after STT success {}", kv(task));
            return;
        }
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

    private Mono<TranscriptResult> persistTranscriptResult(
            final TranscriptResult result, final ForwardTask task) {
        if (result == null || result.text() == null || result.text().isBlank()) {
            counter("transcript_sink_skipped_empty").increment();
            return Mono.justOrEmpty(result);
        }
        if (!cfg.getTranscriptResultStream().isEnabled()) {
            return Mono.error(new TranscriptResultDeliveryException(
                    new IllegalStateException("direct-STT transcript result stream is disabled")));
        }
        return Mono.fromRunnable(() -> transcriptResultSink.emit(result, transcriptContext(task)))
                .subscribeOn(transcriptSinkScheduler)
                // XADD can be retried at-least-once: the downstream source-window identity
                // makes an acknowledgement-loss duplicate idempotent, while a drop is not.
                .retryWhen(Retry.backoff(5, Duration.ofMillis(100))
                        .maxBackoff(Duration.ofSeconds(2))
                        .jitter(0.2d)
                        .doBeforeRetry(signal -> counter("transcript_sink_retry").increment()))
                .doOnSuccess(ignored -> {
                    counter("transcript_sink_success").increment();
                    log.info("Direct-STT transcript result routed {} textLen={} sttLang={} model={}",
                            kv(task), result.textLength(), result.language(), result.model());
                })
                .onErrorMap(error -> {
                    counter("transcript_sink_error").increment();
                    log.warn("ALERT direct-STT transcript result delivery exhausted err={} {}",
                            error.getClass().getSimpleName(), kv(task));
                    return new TranscriptResultDeliveryException(error);
                })
                .thenReturn(result);
    }

    private static DirectSttTranscriptResultContext transcriptContext(final ForwardTask task) {
        return new DirectSttTranscriptResultContext(
                task.sessionId(),
                task.tenantId(),
                task.userId(),
                task.lastChunkSeq(),
                task.chunkStartedAtMs(),
                task.windowSeq(),
                task.firstChunkSeq(),
                task.lastChunkSeq(),
                task.chunkStartedAtMs(),
                task.chunkStartedAtMs() + task.audioDurationMs(),
                task.audioDurationMs(),
                task.flushReason(),
                task.meetingId(),
                task.deviceId(),
                task.language(),
                task.audioFormat(),
                task.sampleRateHz(),
                task.channels(),
                task.correlationId(),
                task.sha256(),
                task.length());
    }

    private void releaseOnce(final AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            inFlight.release();
        }
    }

    private static void clearAudio(final ForwardTask task) {
        java.util.Arrays.fill(task.audio(), (byte) 0);
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
                + " windowSeq=" + task.windowSeq()
                + " chunkRange=" + task.firstChunkSeq() + "-" + task.lastChunkSeq()
                + " durationMs=" + task.audioDurationMs()
                + " flushReason=" + task.flushReason()
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

    private static String audioFilename(final AudioFormat audioFormat) {
        return switch (audioFormat) {
            case WAV -> "chunk.wav";
            case WEBM_OPUS -> "chunk.webm";
            case PCM16 -> "chunk.pcm";
            case MP3 -> "chunk.mp3";
            case M4A -> "chunk.m4a";
            case OGG -> "chunk.ogg";
            case FLAC -> "chunk.flac";
        };
    }

    /**
     * Immutable forwarding task captured under the monitor — carries the COPIED audio plus
     * routing metadata, so {@code forward()} has no reference to request-scoped state.
     */
    record ForwardTask(
            byte[] audio,
            String sessionId,
            Long tenantId,
            Long userId,
            long windowSeq,
            long firstChunkSeq,
            long lastChunkSeq,
            long chunkStartedAtMs,
            int audioDurationMs,
            String flushReason,
            String meetingId,
            String deviceId,
            String language,
            String audioFormat,
            int sampleRateHz,
            int channels,
            String correlationId,
            String sha256,
            int length,
            /**
             * The audio bound's refund for the frames THIS task holds. Single-shot, so
             * every terminal path can call it: success, error, timeout, cancel, saturation
             * drop, a rejected schedule and shutdown all do, and they overlap in practice.
             */
            DirectSttAudioAccountant.Refund refund) {
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

    private static final class TranscriptResultDeliveryException extends RuntimeException {
        private TranscriptResultDeliveryException(final Throwable cause) {
            super("direct-STT transcript result delivery failed", cause);
        }
    }
}
