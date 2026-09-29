package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.service.AudioChunkDispatcher.SessionDiscardCommand;
import com.example.audiogateway.service.AudioChunkDispatcher.SessionFinishCommand;
import com.example.audiogateway.service.DirectSttForwardingDispatcher.ForwardTask;

import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

/**
 * #3746 (design D4): tells live-stt a session's window forwards are complete so the
 * post-session diarization batch can start on whole-session audio.
 *
 * <p>The finish call must fire only after every in-flight window forward for that
 * session has reached a terminal state — the aggregator flushes the LAST window inside
 * {@code finishSession} and forwards are async, so notifying immediately would race the
 * tail window into live-stt's store and cancel attribution on a sample-count mismatch.
 * This tracker counts outstanding forwards per session+epoch and fires on whichever
 * comes last: the finish signal or the final forward's terminal callback.
 *
 * <p>Delivery is fire-and-forget best-effort over the SAME WebClient (and therefore the
 * same mTLS tunnel) as the {@code /transcribe} forward: an unreachable live-stt can never
 * delay or fail the gateway's own finish response. live-stt cross-checks
 * {@code expectedSampleCount} against its store and cancels attribution on mismatch, so
 * a lost or early notification degrades to "no attribution", never to wrong labels.
 *
 * <p>Only SUCCESSFUL forwards count into {@code expectedSampleCount}: a dropped or
 * failed forward never reached the store, and live-stt's whole-session integrity check
 * is exactly what turns that gap into a clean cancel instead of misaligned labels.
 *
 * <p>PII boundary: this class touches no audio and no text; the payload carries ids,
 * an epoch and a sample count. Logs carry ids and counts only.
 */
public class DirectSttAttributionFinishNotifier {

    private static final Logger log =
            LoggerFactory.getLogger(DirectSttAttributionFinishNotifier.class);
    private static final String METRIC_PREFIX = "audio_gateway_direct_stt_attribution_";
    private static final String TRANSCRIBE_SUFFIX = "/transcribe";

    private final WebClient webClient;
    private final AudioGatewayProperties.DirectStt cfg;
    private final MeterRegistry meters;
    private final String finishUriBase;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    private static final class SessionState {
        private final String sessionId;
        private final long epoch;
        private final String meetingId;
        private final long tenantId;
        private final AtomicInteger outstanding = new AtomicInteger();
        private final AtomicLong forwardedSamples = new AtomicLong();
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private final AtomicBoolean fired = new AtomicBoolean(false);

        private SessionState(
                final String sessionId, final long epoch,
                final String meetingId, final long tenantId) {
            this.sessionId = sessionId;
            this.epoch = epoch;
            this.meetingId = meetingId;
            this.tenantId = tenantId;
        }
    }

    public DirectSttAttributionFinishNotifier(
            final WebClient webClient,
            final AudioGatewayProperties.DirectStt cfg,
            final MeterRegistry meters) {
        this.webClient = webClient;
        this.cfg = cfg;
        this.meters = meters;
        this.finishUriBase = deriveFinishBase(cfg.getTranscribeUrl());
        if (cfg.getAttributionFinish().isEnabled() && finishUriBase == null) {
            // Fail loudly at construction: an enabled notifier that can never fire is a
            // silent no-attribution regression, the worst failure shape for this seam.
            throw new IllegalStateException(
                    "direct-stt attribution-finish enabled but transcribe-url does not end "
                            + "with /transcribe; cannot derive the finish endpoint");
        }
    }

    private static String deriveFinishBase(final String transcribeUrl) {
        if (transcribeUrl == null || !transcribeUrl.endsWith(TRANSCRIBE_SUFFIX)) {
            return null;
        }
        return transcribeUrl.substring(0, transcribeUrl.length() - TRANSCRIBE_SUFFIX.length());
    }

    private boolean disabled() {
        return !cfg.getAttributionFinish().isEnabled();
    }

    private static String key(final String sessionId, final long epoch) {
        return sessionId + ":" + epoch;
    }

    /** Called before a window forward is scheduled (inside the admission path — O(1)). */
    public void onForwardStart(final ForwardTask task) {
        if (disabled()) {
            return;
        }
        if (task.tenantId() == null || task.meetingId() == null || task.meetingId().isBlank()) {
            // No identity envelope -> live-stt could never accept a finish for it.
            return;
        }
        final SessionState state = sessions.computeIfAbsent(
                key(task.sessionId(), task.epoch()),
                ignored -> new SessionState(
                        task.sessionId(), task.epoch(), task.meetingId(), task.tenantId()));
        state.outstanding.incrementAndGet();
    }

    /** Called from the forward's terminal callback (success or any failure). */
    public void onForwardComplete(final ForwardTask task, final boolean success) {
        if (disabled()) {
            return;
        }
        final SessionState state = sessions.get(key(task.sessionId(), task.epoch()));
        if (state == null) {
            return;
        }
        if (success) {
            // PCM16: two bytes per sample; task.length() is the window's audio byte count.
            state.forwardedSamples.addAndGet(task.length() / 2L);
        }
        if (state.outstanding.decrementAndGet() == 0 && state.finished.get()) {
            fire(state);
        }
    }

    /** Called from {@code finishSession} AFTER the aggregator flushed the tail window. */
    public void onSessionFinished(final SessionFinishCommand cmd) {
        if (disabled()) {
            return;
        }
        // Epoch is not on the finish command; a session's forwards all share one live
        // epoch in the REST path, so every tracked epoch for this session is finished.
        sessions.values().stream()
                .filter(state -> state.sessionId.equals(cmd.sessionId()))
                .forEach(state -> {
                    state.finished.set(true);
                    if (state.outstanding.get() == 0) {
                        fire(state);
                    }
                });
    }

    /** Discard means erasure/abort: attribution must not run — drop tracking silently. */
    public void onSessionDiscarded(final SessionDiscardCommand cmd) {
        if (disabled()) {
            return;
        }
        sessions.entrySet().removeIf(e -> e.getValue().sessionId.equals(cmd.sessionId()));
    }

    private void fire(final SessionState state) {
        if (!state.fired.compareAndSet(false, true)) {
            return;
        }
        sessions.remove(key(state.sessionId, state.epoch));
        final long samples = state.forwardedSamples.get();
        if (state.meetingId == null || state.meetingId.isBlank() || samples < 16_000L) {
            // No meeting identity or under one second of forwarded audio: live-stt
            // would reject the envelope anyway; skip the call, keep a metric.
            meters.counter(METRIC_PREFIX + "finish_skipped").increment();
            return;
        }
        final Map<String, Object> payload = Map.of(
                "schema", "liveSttSessionFinish.v1",
                "tenantId", Long.toString(state.tenantId),
                "meetingId", state.meetingId,
                "sourceSessionId", state.sessionId,
                "transportEpoch", state.epoch,
                "expectedSampleCount", samples);
        final String uri = finishUriBase + "/session/"
                + state.sessionId + ":" + state.epoch + "/finish";
        webClient.post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofMillis(cfg.getAttributionFinish().getTimeoutMs()))
                .doOnSuccess(ignored -> meters.counter(METRIC_PREFIX + "finish_sent").increment())
                .onErrorResume(error -> {
                    meters.counter(METRIC_PREFIX + "finish_failed").increment();
                    log.warn("Attribution finish notification failed sessionId={} epoch={} "
                                    + "err={}",
                            state.sessionId, state.epoch, error.getClass().getSimpleName());
                    return Mono.empty();
                })
                .subscribe();
    }

    /** Visible for tests: how many sessions are currently tracked. */
    int trackedSessions() {
        return sessions.size();
    }
}
