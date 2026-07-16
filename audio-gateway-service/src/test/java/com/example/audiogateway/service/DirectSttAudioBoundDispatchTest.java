package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.service.AudioChunkDispatcher.ChunkDispatchCommand;
import com.example.audiogateway.service.AudioChunkDispatcher.DispatchOutcome;
import com.example.audiogateway.service.AudioChunkDispatcher.SessionDiscardCommand;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Sinks;

/**
 * The #428 / platform-ai#257 audio bound, at its dispatch seam.
 *
 * <p>These assert the GATE's outcome (accept / 429 / 503) and never reach the forward HTTP,
 * so they run without a live loopback — unlike {@link DirectSttForwardingDispatcherTest},
 * whose forward-mechanics cases need a reachable MockWebServer. The two split deliberately:
 * the bound's decision is exactly the part that must be provable everywhere, including a
 * sandbox with no loopback.
 *
 * <p>The measurement behind the owner decision: the Redis stream carries only a hash +
 * routing metadata, never audio, so this bound lives in-process on the Direct-STT
 * lifecycle rather than on any Redis counter.
 */
class DirectSttAudioBoundDispatchTest {

    private static final long TENANT = 42L;
    private static final int RATE_16K = 16_000;
    private static final int MONO = 1;

    private final MeterRegistry meters = new SimpleMeterRegistry();

    /** A base dispatcher that always accepts — isolates the audio bound as the only gate. */
    private static AudioChunkDispatcher acceptDelegate() {
        return cmd -> new DispatchOutcome.Accepted();
    }

    /**
     * A base delegate that counts how many times it was invoked — stands in for
     * RedisStreamsAudioChunkDispatcher, whose invocation IS the XADD. A refusal by the
     * audio bound must leave this at zero: no descriptor written for a rejected chunk.
     */
    static final class CountingDelegate implements AudioChunkDispatcher {
        final AtomicInteger invocations = new AtomicInteger();
        private final DispatchOutcome outcome;

        CountingDelegate(final DispatchOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public DispatchOutcome dispatch(final ChunkDispatchCommand cmd) {
            invocations.incrementAndGet();
            return outcome;
        }
    }

    private DirectSttForwardingDispatcher dispatcherWith(
            final AudioChunkDispatcher delegate, final AudioGatewayProperties props) {
        return new DirectSttForwardingDispatcher(
                delegate, noopAudit(), DirectSttTranscriptResultSink.noop(),
                WebClient.builder().build(), props, meters);
    }

    private AudioGatewayProperties props(final int maxBufferedSeconds) {
        final AudioGatewayProperties p = new AudioGatewayProperties();
        p.getDirectStt().setEnabled(true);
        p.getDirectStt().setTranscribeUrl("http://127.0.0.1:1/transcribe");
        p.getDirectStt().setMaxInFlight(8);
        // Aggregation ON with a window far larger than the bound. A reserved chunk then sits
        // in the aggregator and never flushes, so no async forward fires and no refund races
        // the next dispatch — the reservation is held synchronously and the gate's decision
        // is deterministic. (An accepted chunk under aggregation-off would forward, fail to
        // connect, and refund on another thread, making the over-limit assertion flaky.)
        p.getDirectStt().getAggregation().setEnabled(true);
        p.getDirectStt().getAggregation().setWindowSeconds(Math.max(200, maxBufferedSeconds + 100));
        p.getDirectStt().getAggregation().setMaxBufferedSessions(16);
        p.getBounds().setMaxBufferedSeconds(maxBufferedSeconds);
        return p;
    }

    private DirectSttForwardingDispatcher dispatcher(final AudioGatewayProperties props) {
        return new DirectSttForwardingDispatcher(
                acceptDelegate(), noopAudit(), DirectSttTranscriptResultSink.noop(),
                WebClient.builder().build(), props, meters);
    }

    private static AudioGatewayAuditSink noopAudit() {
        return event -> { };
    }

    private static ChunkDispatchCommand pcm16(final String sessionId, final int byteLength) {
        return command(sessionId, AudioFormat.PCM16, RATE_16K, MONO, byteLength);
    }

    private static ChunkDispatchCommand command(
            final String sessionId, final AudioFormat format,
            final int sampleRateHz, final int channels, final int byteLength) {
        final AudioChunkPayload payload =
                AudioChunkPayload.of(new byte[byteLength], "deadbeefcafe0000sha");
        return new ChunkDispatchCommand(
                sessionId, TENANT, 7L, "22222222-2222-4222-8222-222222222222",
                "iphone-1", "tr", format, sampleRateHz, channels, 0L, 1_000L, "corr", payload);
    }

    private static int seconds16kMono(final double seconds) {
        return (int) Math.round(seconds * RATE_16K) * MONO * 2;
    }

    private static double counter(final MeterRegistry meters, final String name) {
        final var c = meters.find("audio_gateway_direct_stt_" + name).counter();
        return c == null ? 0.0 : c.count();
    }

    private static double gauge(final MeterRegistry meters, final String name) {
        return meters.get("audio_gateway_direct_stt_" + name).gauge().value();
    }

    private static void awaitGauge(
            final MeterRegistry meters, final String name, final double expected) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && gauge(meters, name) != expected) {
            Thread.sleep(10L);
        }
        assertThat(gauge(meters, name)).isEqualTo(expected);
    }

    // ────────────────────────── within the bound ──────────────────────────

    @Test
    void exactlyTheBoundIsAccepted() {
        DirectSttForwardingDispatcher dispatcher = dispatcher(props(30));

        DispatchOutcome out = dispatcher.dispatch(pcm16("SES-1", seconds16kMono(30)));

        assertThat(out).isInstanceOf(DispatchOutcome.Accepted.class);
    }

    @Test
    void oneFrameBeyondTheBoundIs429WithRetryAfter() {
        DirectSttForwardingDispatcher dispatcher = dispatcher(props(30));
        dispatcher.dispatch(pcm16("SES-1", seconds16kMono(30)));

        DispatchOutcome out = dispatcher.dispatch(pcm16("SES-1", MONO * 2));

        assertThat(out).isInstanceOfSatisfying(DispatchOutcome.QueueFull.class,
                qf -> assertThat(qf.retryAfterSeconds()).isPositive());
        assertThat(counter(meters, "audio_bound_over_limit")).isEqualTo(1.0);
    }

    @Test
    void twoSessionsDoNotShareTheBound() {
        DirectSttForwardingDispatcher dispatcher = dispatcher(props(30));
        dispatcher.dispatch(pcm16("SES-1", seconds16kMono(30)));

        assertThat(dispatcher.dispatch(pcm16("SES-2", seconds16kMono(30))))
                .as("one session at its bound must not throttle another")
                .isInstanceOf(DispatchOutcome.Accepted.class);
    }

    @Test
    void expiryDiscardsOnlyBufferedTailAndReopensAggregatorCapacity() {
        final AudioGatewayProperties properties = props(30);
        properties.getDirectStt().getAggregation().setMaxBufferedSessions(1);
        final DirectSttForwardingDispatcher dispatcher = dispatcher(properties);
        dispatcher.dispatch(pcm16("SES-1", seconds16kMono(1)));
        assertThat(gauge(meters, "aggregation_active_sessions")).isEqualTo(1.0);
        assertThat(gauge(meters, "audio_bound_reserved_frames")).isEqualTo(RATE_16K);

        dispatcher.discardSession(new SessionDiscardCommand(
                "SES-1", TENANT, 7L, "expiry-correlation"));

        assertThat(gauge(meters, "aggregation_active_sessions")).isZero();
        assertThat(gauge(meters, "audio_bound_reserved_frames")).isZero();
        assertThat(gauge(meters, "audio_bound_negative_invariant_total")).isZero();
        assertThat(dispatcher.dispatch(pcm16("SES-2", seconds16kMono(1))))
                .as("discarded session must release the sole aggregation slot")
                .isInstanceOf(DispatchOutcome.Accepted.class);
    }

    @Test
    void expiryDoesNotEarlyRefundAnInFlightWindow() throws Exception {
        final AudioGatewayProperties properties = props(2);
        properties.getDirectStt().getAggregation().setWindowSeconds(1);
        final CountDownLatch requestStarted = new CountDownLatch(1);
        final Sinks.One<ClientResponse> response = Sinks.one();
        final WebClient pendingClient = WebClient.builder()
                .exchangeFunction(request -> {
                    requestStarted.countDown();
                    return response.asMono();
                })
                .build();
        final DirectSttForwardingDispatcher dispatcher = new DirectSttForwardingDispatcher(
                acceptDelegate(), noopAudit(), DirectSttTranscriptResultSink.noop(),
                pendingClient, properties, meters);

        assertThat(dispatcher.dispatch(pcm16("SES-1", seconds16kMono(1))))
                .isInstanceOf(DispatchOutcome.Accepted.class);
        assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(gauge(meters, "audio_bound_reserved_frames")).isEqualTo(RATE_16K);

        dispatcher.discardSession(new SessionDiscardCommand(
                "SES-1", TENANT, 7L, "expiry-during-forward"));

        assertThat(gauge(meters, "aggregation_active_sessions")).isZero();
        assertThat(gauge(meters, "audio_bound_reserved_frames"))
                .as("in-flight bytes still occupy heap and must remain charged")
                .isEqualTo(RATE_16K);
        assertThat(gauge(meters, "audio_bound_negative_invariant_total")).isZero();

        response.tryEmitValue(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"text\":\"ok\",\"language\":\"tr\",\"elapsed_ms\":10.0}")
                .build());

        awaitGauge(meters, "audio_bound_reserved_frames", 0.0);
        assertThat(gauge(meters, "audio_bound_negative_invariant_total")).isZero();
        dispatcher.destroy();
    }

    // ────────────────────────── unmeterable → 503, never a silent pass ──────────────────────────

    @Test
    void nonPcm16FormatsAre503_notAdmittedAsIfMeterable() {
        // The owner decision: direct-STT/live mode is PCM16-only. WAV/WEBM_OPUS stay in the
        // global API contract, but here their duration cannot be derived, so they are an
        // explicit Unavailable rather than a silent pass whose byte length would be metered
        // as if it were raw PCM (30s of Opus reads as ~2s — the original P1-a defect).
        for (AudioFormat compressed : new AudioFormat[] {AudioFormat.WAV, AudioFormat.WEBM_OPUS}) {
            MeterRegistry local = new SimpleMeterRegistry();
            DirectSttForwardingDispatcher dispatcher = new DirectSttForwardingDispatcher(
                    acceptDelegate(), noopAudit(), DirectSttTranscriptResultSink.noop(),
                    WebClient.builder().build(), props(30), local);

            DispatchOutcome out = dispatcher.dispatch(
                    command("SES-1", compressed, RATE_16K, MONO, 4_096));

            assertThat(out)
                    .as("%s must be 503 in direct-STT mode", compressed)
                    .isInstanceOf(DispatchOutcome.Unavailable.class);
            assertThat(local.find("audio_gateway_direct_stt_audio_capacity_unknown")
                    .tag("reason", "non_pcm16_format").counter())
                    .as("503 carries a low-cardinality reason label").isNotNull();
        }
    }

    @Test
    void invalidPcmParamsAre503() {
        DirectSttForwardingDispatcher dispatcher = dispatcher(props(30));

        assertThat(dispatcher.dispatch(command("SES-1", AudioFormat.PCM16, 0, MONO, 3_200)))
                .isInstanceOf(DispatchOutcome.Unavailable.class);
        assertThat(counter(meters, "audio_capacity_unknown")).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void aPartialPcmFrameIs503() {
        DirectSttForwardingDispatcher dispatcher = dispatcher(props(30));
        // Stereo: 4 bytes per frame. 4097 is not whole frames.
        assertThat(dispatcher.dispatch(command("SES-1", AudioFormat.PCM16, RATE_16K, 2, 4_097)))
                .isInstanceOf(DispatchOutcome.Unavailable.class);
    }

    // ─────────────── reservation BEFORE the delegate (no orphan descriptor) ───────────────

    @Test
    void overLimitDoesNotInvokeTheDelegate_soNoOrphanDescriptorIsWritten() {
        // The P1 fix: the audio bound is charged before the delegate. In Redis mode the
        // delegate's dispatch() IS the XADD, so a 429 refusal must not have called it — else
        // a rejected chunk would leave a Redis descriptor the client was told was refused.
        CountingDelegate delegate = new CountingDelegate(new DispatchOutcome.Accepted());
        DirectSttForwardingDispatcher dispatcher = dispatcherWith(delegate, props(30));

        // Fill the bound (this dispatch DOES pass through the delegate once).
        dispatcher.dispatch(pcm16("SES-1", seconds16kMono(30)));
        int afterFill = delegate.invocations.get();

        DispatchOutcome out = dispatcher.dispatch(pcm16("SES-1", MONO * 2)); // one frame over

        assertThat(out).isInstanceOf(DispatchOutcome.QueueFull.class);
        assertThat(delegate.invocations.get())
                .as("an over-limit chunk must not reach the delegate (no XADD)")
                .isEqualTo(afterFill);
    }

    @Test
    void unmeterableDoesNotInvokeTheDelegate_soNoOrphanDescriptorIsWritten() {
        CountingDelegate delegate = new CountingDelegate(new DispatchOutcome.Accepted());
        DirectSttForwardingDispatcher dispatcher = dispatcherWith(delegate, props(30));

        DispatchOutcome out = dispatcher.dispatch(command("SES-1", AudioFormat.WAV, RATE_16K, MONO, 4_096));

        assertThat(out).isInstanceOf(DispatchOutcome.Unavailable.class);
        assertThat(delegate.invocations.get())
                .as("a 503-unmeterable chunk must not reach the delegate (no XADD)")
                .isZero();
    }

    @Test
    void reservedButDelegateRejects_refundsTheReservationFully() {
        // Reservation succeeds, then the base gate refuses (its own backpressure). The
        // reservation must be released terminally — otherwise a base QueueFull would leak
        // capacity. Toggle the delegate: reject first, then accept; if the refund happened,
        // the whole bound is free again on the second try (else it would 429 at 30+30).
        final boolean[] rejectNext = {true};
        AudioChunkDispatcher togglingDelegate = cmd ->
                rejectNext[0] ? new DispatchOutcome.QueueFull(9L) : new DispatchOutcome.Accepted();
        DirectSttForwardingDispatcher dispatcher = dispatcherWith(togglingDelegate, props(30));

        DispatchOutcome refused = dispatcher.dispatch(pcm16("SES-1", seconds16kMono(30)));
        assertThat(refused).isInstanceOfSatisfying(DispatchOutcome.QueueFull.class,
                qf -> assertThat(qf.retryAfterSeconds()).isEqualTo(9L));

        rejectNext[0] = false;
        assertThat(dispatcher.dispatch(pcm16("SES-1", seconds16kMono(30))))
                .as("a base-gate rejection refunds the reservation fully, so the bound reopens")
                .isInstanceOf(DispatchOutcome.Accepted.class);
    }
}
