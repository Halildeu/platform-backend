package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.service.AudioChunkDispatcher.ChunkDispatchCommand;
import com.example.audiogateway.service.AudioChunkDispatcher.DispatchOutcome;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

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

    // ────────────────────────── the base gate still comes first ──────────────────────────

    @Test
    void aDelegateRejectionShortCircuitsBeforeTheBound() {
        // The audio bound must not reserve for a chunk the base dispatcher already refused —
        // otherwise a rejected chunk would consume capacity it never used. Same dispatcher,
        // same accountant: the delegate rejects the first chunk, then accepts, and the full
        // bound is still free — proving the refused chunk charged nothing.
        final boolean[] rejectNext = {true};
        AudioChunkDispatcher togglingDelegate = cmd ->
                rejectNext[0] ? new DispatchOutcome.QueueFull(9L) : new DispatchOutcome.Accepted();
        DirectSttForwardingDispatcher dispatcher = new DirectSttForwardingDispatcher(
                togglingDelegate, noopAudit(), DirectSttTranscriptResultSink.noop(),
                WebClient.builder().build(), props(30), meters);

        DispatchOutcome refused = dispatcher.dispatch(pcm16("SES-1", seconds16kMono(30)));
        assertThat(refused).isInstanceOfSatisfying(DispatchOutcome.QueueFull.class,
                qf -> assertThat(qf.retryAfterSeconds()).isEqualTo(9L));

        rejectNext[0] = false;
        assertThat(dispatcher.dispatch(pcm16("SES-1", seconds16kMono(30))))
                .as("the refused chunk charged nothing, so the whole bound is still free")
                .isInstanceOf(DispatchOutcome.Accepted.class);
    }
}
