package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.TranscriptResult;
import com.example.audiogateway.service.AudioChunkDispatcher.ChunkDispatchCommand;
import com.example.audiogateway.service.AudioChunkDispatcher.DispatchOutcome;
import com.example.audiogateway.service.AudioChunkDispatcher.SessionFinishCommand;
import com.example.audiogateway.service.AudioGatewayAuditSink.AuditEvent;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Unit test for {@link DirectSttForwardingDispatcher} (Faz 24 issue #182 CORE) using
 * {@link MockWebServer} as a stand-in for live-stt {@code /transcribe}.
 *
 * <p>Covers the required scope: POSTs the audio as a multipart {@code audio} part + query
 * params, parses the transcript, and tolerates an STT error without affecting admission;
 * plus the hardening contracts from Codex {@code 019eeb5f} (drop-on-saturation, respect the
 * delegate's backpressure, ByteBuffer position untouched).
 */
class DirectSttForwardingDispatcherTest {

    private MockWebServer server;
    private WebClient webClient;
    private MeterRegistry meters;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        final HttpClient httpClient = HttpClient.create();
        webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private AudioGatewayProperties props(final int maxInFlight) {
        final AudioGatewayProperties p = new AudioGatewayProperties();
        p.getDirectStt().setEnabled(true);
        p.getDirectStt().setTranscribeUrl(server.url("/transcribe").toString());
        p.getDirectStt().setMaxInFlight(maxInFlight);
        p.getDirectStt().setConnectTimeoutMs(3_000);
        p.getDirectStt().setResponseTimeoutMs(5_000);
        p.getDirectStt().setMaxResponseBytes(262_144);
        p.getDirectStt().getAggregation().setEnabled(false);
        p.getDirectStt().getTranscriptResultStream().setEnabled(true);
        return p;
    }

    private static ChunkDispatchCommand command(final byte[] audio) {
        // Direct-STT is PCM16-only (platform-ai#257): a plain forward-mechanics command is
        // PCM16. Non-PCM16 formats are 503 at the audio bound and covered by
        // DirectSttAudioBoundDispatchTest, not here.
        return command(audio, AudioFormat.PCM16);
    }

    private static ChunkDispatchCommand command(final byte[] audio, final AudioFormat audioFormat) {
        return command(audio, audioFormat, 0L, 1_000L);
    }

    private static ChunkDispatchCommand command(
            final byte[] audio,
            final AudioFormat audioFormat,
            final long chunkSeq,
            final long chunkStartedAtMs) {
        return command("SES-abc", audio, audioFormat, chunkSeq, chunkStartedAtMs);
    }

    private static ChunkDispatchCommand command(
            final String sessionId,
            final byte[] audio,
            final AudioFormat audioFormat,
            final long chunkSeq,
            final long chunkStartedAtMs) {
        final AudioChunkPayload payload = AudioChunkPayload.of(audio, "deadbeefcafe0000sha");
        return new ChunkDispatchCommand(
                sessionId, 42L, 7L, "22222222-2222-4222-8222-222222222222", "iphone-h-1", "tr",
                audioFormat, 16_000, 1, chunkSeq, chunkStartedAtMs, "corr-xyz", payload);
    }

    private static double counter(final MeterRegistry meters, final String name) {
        final var c = meters.find("audio_gateway_direct_stt_" + name).counter();
        return c == null ? 0.0 : c.count();
    }

    /** Always-Accepted delegate (NoOp-equivalent) for the success/forward tests. */
    private static AudioChunkDispatcher acceptDelegate() {
        return cmd -> new DispatchOutcome.Accepted();
    }

    private static byte[] pcm16Seconds(final int seconds) {
        return new byte[16_000 * 2 * seconds];
    }

    private static byte[] pcm16Millis(final int millis) {
        return new byte[(16_000 * 2 * millis) / 1_000];
    }

    private static RecordingAuditSink recordingAuditSink() {
        return new RecordingAuditSink();
    }

    private static DirectSttForwardingDispatcher dispatcher(
            final AudioChunkDispatcher delegate,
            final WebClient webClient,
            final AudioGatewayProperties props,
            final MeterRegistry meters,
            final AudioGatewayAuditSink auditSink) {
        return dispatcher(delegate, webClient, props, meters, auditSink, DirectSttTranscriptResultSink.noop());
    }

    private static DirectSttForwardingDispatcher dispatcher(
            final AudioChunkDispatcher delegate,
            final WebClient webClient,
            final AudioGatewayProperties props,
            final MeterRegistry meters,
            final AudioGatewayAuditSink auditSink,
            final DirectSttTranscriptResultSink transcriptResultSink) {
        return new DirectSttForwardingDispatcher(
                delegate, auditSink, transcriptResultSink, webClient, props, meters);
    }

    @Test
    void registersRuntimeAcceptanceCountersAtZeroBeforeFirstDispatch() {
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props(1), meters, recordingAuditSink());

        assertThat(meters.get("audio_gateway_direct_stt_aggregation_chunks_buffered")
                .counter().count()).isZero();
        assertThat(meters.get("audio_gateway_direct_stt_aggregation_dropped_capacity")
                .counter().count()).isZero();
        dispatcher.destroy();
    }

    @Test
    void forwardsAudioMultipartAndParsesTranscriptOnAccepted() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"merhaba dunya\",\"language\":\"tr\","
                        + "\"language_probability\":0.98,\"duration\":1.2,\"elapsed_ms\":345.6,"
                        + "\"model\":\"large-v3\",\"compute_type\":\"int8\",\"device\":\"cuda\","
                        + "\"segments\":[{\"start\":0.0,\"end\":1.2,\"text\":\"merhaba dunya\"}]}"));

        final RecordingAuditSink auditSink = recordingAuditSink();
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props(8), meters, auditSink);

        final byte[] audio = new byte[] {10, 20, 30, 40, 50, 60};
        final DispatchOutcome out = dispatcher.dispatch(command(audio));

        // (1) Admission returns Accepted IMMEDIATELY (does not await the HTTP response).
        assertThat(out).isInstanceOf(DispatchOutcome.Accepted.class);

        // (2) The fire-and-forget POST actually reaches /transcribe with the audio + query.
        final RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).as("direct-STT POST must reach /transcribe").isNotNull();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).startsWith("/transcribe");
        assertThat(req.getPath())
                .contains("meeting_id=22222222-2222-4222-8222-222222222222")
                .contains("session_id=SES-abc")
                .contains("device_id=iphone-h-1")
                .contains("language=tr");
        final String contentType = req.getHeader("Content-Type");
        assertThat(contentType).contains("multipart/form-data");
        final String bodyUtf8 = req.getBody().readUtf8();
        // multipart contains the "audio" part with the raw bytes (10,20,30,40,50).
        assertThat(bodyUtf8).contains("name=\"audio\"");
        assertThat(bodyUtf8)
                .contains("Content-Disposition: form-data; name=\"audio\"; filename=\"chunk.wav\"")
                .contains("Content-Type: audio/wav");
        assertThat(bodyUtf8.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("multipart body must carry the raw audio bytes")
                .contains(new byte[] {10, 20, 30, 40, 50, 60});

        // (3) Success metric increments (await the async callback), no transcript text leaked.
        awaitCounter(meters, "success", 1.0);
        awaitCounter(meters, "transcript_sink_success", 1.0);
        assertThat(counter(meters, "attempted")).isEqualTo(1.0);
        assertThat(counter(meters, "http_error")).isZero();
        assertThat(counter(meters, "exception")).isZero();
        assertThat(auditSink.events())
                .filteredOn(AuditEvent.ChunkForwardedToComputePlane.class::isInstance)
                .hasSize(1)
                .first()
                .satisfies(e -> {
                    final AuditEvent.ChunkForwardedToComputePlane f =
                            (AuditEvent.ChunkForwardedToComputePlane) e;
                    assertThat(f.sessionId()).isEqualTo("SES-abc");
                    assertThat(f.tenantId()).isEqualTo(42L);
                    assertThat(f.userId()).isEqualTo(7L);
                    assertThat(f.meetingId()).isEqualTo("22222222-2222-4222-8222-222222222222");
                    assertThat(f.chunkSeq()).isZero();
                    assertThat(f.windowSeq()).isZero();
                    assertThat(f.firstChunkSeq()).isZero();
                    assertThat(f.lastChunkSeq()).isZero();
                    assertThat(f.windowStartedAtMs()).isEqualTo(1_000L);
                    assertThat(f.flushReason()).isEqualTo("chunk");
                    assertThat(f.sha256()).isEqualTo("deadbeefcafe0000sha");
                    assertThat(f.byteLength()).isEqualTo(6);
                    assertThat(f.computePlane()).isEqualTo("live-stt");
                });
    }

    // (forwardsMultipartAudioPartUsingSessionAudioFormat removed: it forwarded WEBM_OPUS to
    // assert the session's format was preserved on the wire. Under the platform-ai#257
    // owner decision, direct-STT is PCM16-only and a WEBM_OPUS chunk is now a 503 at the
    // audio bound — proven in DirectSttAudioBoundDispatchTest.nonPcm16FormatsAre503 — so it
    // never reaches the forward, and this case no longer describes a reachable path.)

    @Test
    void wrapsPcm16IntoWavContainerForLiveStt() throws Exception {
        // The recorder uploads raw headerless PCM16; live-stt rejects it (HTTP 400) unless it
        // is wrapped into a WAV container (proven live: raw PCM + audio/L16 → 400). The gateway
        // must synthesize a WAV header and send audio/wav — NEVER raw PCM / audio/L16 / chunk.pcm.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"merhaba\",\"language\":\"tr\"}"));

        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props(8), meters, recordingAuditSink());

        final byte[] rawPcm = {10, 20, 30, 40, 50, 60};
        final DispatchOutcome out = dispatcher.dispatch(command(rawPcm, AudioFormat.PCM16));

        assertThat(out).isInstanceOf(DispatchOutcome.Accepted.class);
        final RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).as("PCM16 chunk must still be forwarded").isNotNull();
        final byte[] bodyBytes = req.getBody().readByteArray();
        final String bodyLatin1 = new String(bodyBytes, java.nio.charset.StandardCharsets.ISO_8859_1);

        // The audio part is advertised as a WAV file — never raw PCM / audio/L16 / chunk.pcm.
        assertThat(bodyLatin1)
                .contains("Content-Disposition: form-data; name=\"audio\"; filename=\"chunk.wav\"")
                .contains("Content-Type: audio/wav")
                .doesNotContain("audio/L16")
                .doesNotContain("chunk.pcm");
        // The audio part carries a real RIFF/WAVE container, not the bare PCM bytes.
        assertThat(bodyLatin1).contains("RIFF").contains("WAVE").contains("data");
        // The original PCM samples are preserved inside the container.
        assertThat(bodyBytes)
                .as("WAV-wrapped body must still carry the original PCM samples")
                .contains(rawPcm);

        awaitCounter(meters, "success", 1.0);
        assertThat(counter(meters, "attempted")).isEqualTo(1.0);
        assertThat(counter(meters, "http_error")).isZero();
    }

    @Test
    void aggregatesPcm16ChunksIntoOneWindowBeforeForwarding() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"bes saniye\",\"language\":\"tr\","
                        + "\"elapsed_ms\":700.0}"));

        final AudioGatewayProperties props = props(2);
        props.getDirectStt().getAggregation().setEnabled(true);
        props.getDirectStt().getAggregation().setWindowSeconds(5);
        final RecordingAuditSink auditSink = recordingAuditSink();
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props, meters, auditSink);

        final DispatchOutcome first = dispatcher.dispatch(command(
                pcm16Seconds(2), AudioFormat.PCM16, 0L, 1_000L));
        assertThat(first).isInstanceOf(DispatchOutcome.Accepted.class);
        assertThat(server.takeRequest(300, TimeUnit.MILLISECONDS))
                .as("a partial aggregation window must not call Whisper").isNull();

        final DispatchOutcome second = dispatcher.dispatch(command(
                pcm16Seconds(3), AudioFormat.PCM16, 1L, 3_000L));
        assertThat(second).isInstanceOf(DispatchOutcome.Accepted.class);

        final RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).as("five seconds of PCM must produce one request").isNotNull();
        final byte[] multipart = request.getBody().readByteArray();
        assertThat(new String(multipart, java.nio.charset.StandardCharsets.ISO_8859_1))
                .contains("filename=\"chunk.wav\"")
                .contains("Content-Type: audio/wav")
                .contains("RIFF")
                .contains("WAVE");

        awaitCounter(meters, "success", 1.0);
        assertThat(counter(meters, "attempted")).isEqualTo(1.0);
        assertThat(counter(meters, "aggregation_chunks_buffered")).isEqualTo(2.0);
        assertThat(counter(meters, "aggregation_windows_flushed")).isEqualTo(1.0);
        assertThat(counter(meters, "dropped_saturation")).isZero();
        assertThat(auditSink.events())
                .filteredOn(AuditEvent.ChunkForwardedToComputePlane.class::isInstance)
                .singleElement()
                .satisfies(event -> {
                    final AuditEvent.ChunkForwardedToComputePlane forwarded =
                            (AuditEvent.ChunkForwardedToComputePlane) event;
                    assertThat(forwarded.chunkSeq()).isEqualTo(1L);
                    assertThat(forwarded.byteLength()).isEqualTo(160_000);
                });
        assertThat(meters.find("audio_gateway_direct_stt_real_time_factor")
                .summary().totalAmount()).isGreaterThan(0.0);
    }

    @Test
    void finishFlushesShortTailExactlyOnce() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"kisa kuyruk\",\"language\":\"tr\","
                        + "\"elapsed_ms\":250.0}"));

        final AudioGatewayProperties props = props(2);
        props.getDirectStt().getAggregation().setEnabled(true);
        props.getDirectStt().getAggregation().setWindowSeconds(10);
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props, meters, recordingAuditSink());

        dispatcher.dispatch(command(pcm16Seconds(1), AudioFormat.PCM16, 0L, 1_000L));
        assertThat(server.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();

        final SessionFinishCommand finish =
                new SessionFinishCommand("SES-abc", 42L, 7L, "finish-corr");
        dispatcher.finishSession(finish);
        dispatcher.finishSession(finish);

        assertThat(server.takeRequest(5, TimeUnit.SECONDS))
                .as("finish must flush the short PCM tail").isNotNull();
        assertThat(server.takeRequest(300, TimeUnit.MILLISECONDS))
                .as("idempotent finish must not flush twice").isNull();
        awaitCounter(meters, "success", 1.0);
        assertThat(counter(meters, "attempted")).isEqualTo(1.0);
        assertThat(counter(meters, "aggregation_windows_flushed")).isEqualTo(1.0);
    }

    @Test
    void twelveRecorderChunksBecomeOneFinishWindowWithoutSaturation() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"tek pencere\",\"language\":\"tr\","
                        + "\"elapsed_ms\":300.0}"));

        final AudioGatewayProperties props = props(1);
        props.getDirectStt().getAggregation().setEnabled(true);
        props.getDirectStt().getAggregation().setWindowSeconds(10);
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props, meters, recordingAuditSink());

        for (int seq = 0; seq < 12; seq++) {
            final DispatchOutcome outcome = dispatcher.dispatch(command(
                    pcm16Millis(100), AudioFormat.PCM16, seq, 1_000L + seq * 100L));
            assertThat(outcome).isInstanceOf(DispatchOutcome.Accepted.class);
        }
        assertThat(server.takeRequest(300, TimeUnit.MILLISECONDS))
                .as("100ms recorder chunks must not be forwarded individually").isNull();

        dispatcher.finishSession(new SessionFinishCommand("SES-abc", 42L, 7L, "finish-corr"));

        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
        assertThat(server.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
        awaitCounter(meters, "success", 1.0);
        assertThat(counter(meters, "attempted")).isEqualTo(1.0);
        assertThat(counter(meters, "aggregation_chunks_buffered")).isEqualTo(12.0);
        assertThat(counter(meters, "dropped_saturation")).isZero();
    }

    @Test
    void capacityRejectedChunkIsNotCountedAsBuffered() {
        final AudioGatewayProperties props = props(1);
        props.getDirectStt().getAggregation().setEnabled(true);
        props.getDirectStt().getAggregation().setWindowSeconds(10);
        props.getDirectStt().getAggregation().setMaxBufferedSessions(1);
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props, meters, recordingAuditSink());

        dispatcher.dispatch(command(
                "SES-1", pcm16Millis(100), AudioFormat.PCM16, 0L, 1_000L));
        dispatcher.dispatch(command(
                "SES-2", pcm16Millis(100), AudioFormat.PCM16, 0L, 1_000L));

        assertThat(counter(meters, "aggregation_chunks_buffered")).isEqualTo(1.0);
        assertThat(counter(meters, "aggregation_dropped_capacity")).isEqualTo(1.0);
        dispatcher.destroy();
    }

    @Test
    void shutdownDiscardIsExplicitlyMetered() {
        final AudioGatewayProperties props = props(1);
        props.getDirectStt().getAggregation().setEnabled(true);
        props.getDirectStt().getAggregation().setWindowSeconds(10);
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props, meters, recordingAuditSink());

        dispatcher.dispatch(command(
                pcm16Seconds(1), AudioFormat.PCM16, 0L, 1_000L));
        dispatcher.destroy();

        assertThat(counter(meters, "aggregation_shutdown_discarded_sessions"))
                .isEqualTo(1.0);
        assertThat(counter(meters, "aggregation_shutdown_discarded_bytes"))
                .isEqualTo(32_000.0);
    }

    @Test
    void routesTranscriptToResultSinkWhenEnabled() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"merhaba dunya\",\"language\":\"tr\","
                        + "\"language_probability\":0.98,\"duration\":1.2,\"elapsed_ms\":345.6,"
                        + "\"model\":\"large-v3\",\"compute_type\":\"int8\",\"device\":\"cuda\"}"));

        final AudioGatewayProperties props = props(8);
        props.getDirectStt().getTranscriptResultStream().setEnabled(true);
        final RecordingTranscriptResultSink transcriptSink = new RecordingTranscriptResultSink();
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props, meters, recordingAuditSink(), transcriptSink);

        final DispatchOutcome out = dispatcher.dispatch(command(new byte[] {10, 20, 30, 40}));

        assertThat(out).isInstanceOf(DispatchOutcome.Accepted.class);
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
        awaitCounter(meters, "success", 1.0);
        awaitCounter(meters, "transcript_sink_success", 1.0);
        assertThat(counter(meters, "transcript_sink_error")).isZero();
        assertThat(transcriptSink.results()).hasSize(1);
        assertThat(transcriptSink.results().get(0).text()).isEqualTo("merhaba dunya");
        assertThat(transcriptSink.contexts())
                .hasSize(1)
                .first()
                .satisfies(ctx -> {
                    assertThat(ctx.sessionId()).isEqualTo("SES-abc");
                    assertThat(ctx.meetingId()).isEqualTo("22222222-2222-4222-8222-222222222222");
                    assertThat(ctx.chunkSeq()).isZero();
                    assertThat(ctx.chunkStartedAtMs()).isEqualTo(1_000L);
                    assertThat(ctx.windowSeq()).isZero();
                    assertThat(ctx.firstChunkSeq()).isZero();
                    assertThat(ctx.lastChunkSeq()).isZero();
                    assertThat(ctx.windowStartedAtMs()).isEqualTo(1_000L);
                    assertThat(ctx.flushReason()).isEqualTo("chunk");
                    assertThat(ctx.correlationId()).isEqualTo("corr-xyz");
                    assertThat(ctx.byteLength()).isEqualTo(4);
                });
    }

    @Test
    void transcriptSinkFailureRetriesAndNeverReportsTranscriptSuccess() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"merhaba dunya\",\"language\":\"tr\"}"));

        final AudioGatewayProperties props = props(8);
        props.getDirectStt().getTranscriptResultStream().setEnabled(true);
        final DirectSttTranscriptResultSink failingSink = (result, context) -> {
            throw new org.springframework.dao.QueryTimeoutException("result stream down");
        };
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props, meters, recordingAuditSink(), failingSink);

        final DispatchOutcome out = dispatcher.dispatch(command(new byte[] {10, 20, 30, 40}));

        assertThat(out).isInstanceOf(DispatchOutcome.Accepted.class);
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
        awaitCounter(meters, "transcript_sink_error", 1.0);
        assertThat(counter(meters, "transcript_sink_retry")).isEqualTo(5.0);
        assertThat(counter(meters, "transcript_delivery_failed")).isEqualTo(1.0);
        assertThat(counter(meters, "timeout")).isZero();
        assertThat(counter(meters, "success")).isZero();
        assertThat(counter(meters, "transcript_sink_success")).isZero();
    }

    @Test
    void transcriptSinkThatNeverReturnsReleasesPermitAtTotalDeadline() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"merhaba dunya\",\"language\":\"tr\"}"));

        final AudioGatewayProperties props = props(1);
        props.getDirectStt().getTranscriptResultStream().setDeliveryAttemptTimeoutMs(50L);
        props.getDirectStt().getTranscriptResultStream().setDeliveryTotalTimeoutMs(250L);
        final CountDownLatch enteredSink = new CountDownLatch(1);
        final CountDownLatch releaseSink = new CountDownLatch(1);
        final DirectSttTranscriptResultSink hangingSink = (result, context) -> {
            enteredSink.countDown();
            try {
                releaseSink.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props, meters, recordingAuditSink(), hangingSink);

        try {
            assertThat(dispatcher.dispatch(command(new byte[] {10, 20, 30, 40})))
                    .isInstanceOf(DispatchOutcome.Accepted.class);
            assertThat(enteredSink.await(2, TimeUnit.SECONDS)).isTrue();
            awaitCounter(meters, "transcript_delivery_failed", 1.0);

            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"text\":\"ikinci\",\"language\":\"tr\"}"));
            assertThat(dispatcher.dispatch(command(new byte[] {1, 2, 3, 4})))
                    .as("the first hung XADD must not retain the sole in-flight permit")
                    .isInstanceOf(DispatchOutcome.Accepted.class);
            assertThat(counter(meters, "success")).isZero();
            assertThat(counter(meters, "transcript_sink_success")).isZero();
        } finally {
            releaseSink.countDown();
        }
    }

    @Test
    void emptyTranscriptSkipsResultSinkWhenEnabled() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"\",\"language\":\"tr\"}"));

        final AudioGatewayProperties props = props(8);
        props.getDirectStt().getTranscriptResultStream().setEnabled(true);
        final RecordingTranscriptResultSink transcriptSink = new RecordingTranscriptResultSink();
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props, meters, recordingAuditSink(), transcriptSink);

        final DispatchOutcome out = dispatcher.dispatch(command(new byte[] {10, 20, 30, 40}));

        assertThat(out).isInstanceOf(DispatchOutcome.Accepted.class);
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
        awaitCounter(meters, "success", 1.0);
        awaitCounter(meters, "transcript_sink_skipped_empty", 1.0);
        assertThat(counter(meters, "transcript_sink_success")).isZero();
        assertThat(transcriptSink.results()).isEmpty();
    }

    @Test
    void sttErrorDoesNotAffectAdmission() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props(8), meters, recordingAuditSink());

        final DispatchOutcome out = dispatcher.dispatch(command(new byte[] {1, 2, 3, 4}));

        // Admission still Accepted — STT failure is best-effort, never breaks ingest.
        assertThat(out).isInstanceOf(DispatchOutcome.Accepted.class);

        // The POST was attempted and the 5xx is classified as http_error{5xx}.
        final RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        awaitCounter(meters, "http_error", 1.0);
        assertThat(counter(meters, "success")).isZero();
        assertThat(meters.find("audio_gateway_direct_stt_http_error")
                .tag("status_family", "5xx").counter())
                .as("5xx classified by status family").isNotNull();
    }

    @Test
    void delegateBackpressureSkipsForward() throws Exception {
        // Delegate rejects with QueueFull → no audio forwarded for a rejected chunk.
        // Two bytes = one whole PCM16 frame: the reservation (taken before the delegate
        // since the #428 ordering fix) succeeds, so the chunk actually reaches the delegate,
        // which is what this test exercises. A 1-byte partial-frame chunk would now be a
        // 503 at the bound and never reach the delegate at all.
        final AudioChunkDispatcher queueFull = cmd -> new DispatchOutcome.QueueFull(5L);
        final RecordingAuditSink auditSink = recordingAuditSink();
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                queueFull, webClient, props(8), meters, auditSink);

        final DispatchOutcome out = dispatcher.dispatch(command(new byte[] {9, 10}));

        assertThat(out).isInstanceOf(DispatchOutcome.QueueFull.class);
        assertThat(((DispatchOutcome.QueueFull) out).retryAfterSeconds()).isEqualTo(5L);
        // No request should ever arrive.
        assertThat(server.takeRequest(750, TimeUnit.MILLISECONDS))
                .as("rejected chunk must NOT be forwarded").isNull();
        assertThat(counter(meters, "attempted")).isZero();
        assertThat(auditSink.events())
                .as("rejected chunks must not emit compute-plane transit proof")
                .isEmpty();
    }

    @Test
    void dropsForwardWhenInFlightSaturated() throws Exception {
        // maxInFlight=1 and a slow server: the first forward holds the only permit, the
        // second dispatch on the monitor finds it saturated and drops (admission unaffected).
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"x\",\"language\":\"tr\"}")
                .setBodyDelay(1500, TimeUnit.MILLISECONDS));

        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props(1), meters, recordingAuditSink());

        // First chunk: acquires the only permit and starts the (slow) forward.
        final DispatchOutcome first = dispatcher.dispatch(command(new byte[] {1, 2}));
        assertThat(first).isInstanceOf(DispatchOutcome.Accepted.class);
        // Ensure the first forward is in-flight (permit taken) before the second dispatch.
        final RecordedRequest firstReq = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(firstReq).isNotNull();

        // Second chunk while saturated → dropped, but admission still Accepted.
        final DispatchOutcome second = dispatcher.dispatch(command(new byte[] {3, 4}));
        assertThat(second).isInstanceOf(DispatchOutcome.Accepted.class);

        awaitCounter(meters, "dropped_saturation", 1.0);
        // The second chunk produced no second POST.
        assertThat(server.takeRequest(500, TimeUnit.MILLISECONDS))
                .as("saturated forward must be dropped, no second POST").isNull();
        // attempted only counts the one that actually ran.
        assertThat(counter(meters, "attempted")).isEqualTo(1.0);
        // Drain the slow first forward before teardown shuts the server (avoid an abrupt-close
        // race) — its permit must be released and the success callback must land.
        awaitCounter(meters, "success", 1.0);
        awaitInFlightDrained(meters, 1);
        // The single forward completed as a clean success, never misclassified.
        assertThat(counter(meters, "http_error")).isZero();
        assertThat(counter(meters, "connection_error")).isZero();
    }

    @Test
    void sourceByteBufferPositionUntouchedByCopy() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"x\",\"language\":\"tr\"}"));

        // AudioChunkPayload.of wraps a read-only buffer with position 0; assert position
        // is still 0 after dispatch (copy must use absolute reads, not relative get()).
        final byte[] audio = new byte[] {5, 6, 7, 8};
        final AudioChunkPayload payload = AudioChunkPayload.of(audio, "abc12345hash");
        final ByteBuffer buf = payload.bytes();
        final int positionBefore = buf.position();

        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props(4), meters, recordingAuditSink());
        final ChunkDispatchCommand cmd = new ChunkDispatchCommand(
                "SES-1", 1L, 1L, "22222222-2222-4222-8222-222222222222", "dev", "tr",
                AudioFormat.PCM16, 16_000, 1, 0L, 0L, "c", payload);

        dispatcher.dispatch(cmd);

        assertThat(buf.position())
                .as("copy must not mutate the request-scoped buffer position")
                .isEqualTo(positionBefore);

        final RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        // Bytes still forwarded intact despite position guard.
        final byte[] body = req.getBody().readByteArray();
        assertThat(new String(body, java.nio.charset.StandardCharsets.ISO_8859_1))
                .contains(new String(new byte[] {5, 6, 7, 8}, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    @Test
    void computePlaneAuditIsEmittedBeforeHttpRequestOnForwardScheduler() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"x\",\"language\":\"tr\"}"));

        final BlockingAuditSink auditSink = new BlockingAuditSink();
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props(4), meters, auditSink);

        final DispatchOutcome out = dispatcher.dispatch(command(new byte[] {1, 2, 3, 4}));
        assertThat(out).isInstanceOf(DispatchOutcome.Accepted.class);

        assertThat(auditSink.awaitStarted())
                .as("audit emission should start asynchronously")
                .isTrue();
        assertThat(server.takeRequest(500, TimeUnit.MILLISECONDS))
                .as("HTTP request must not start before compute-plane audit is released")
                .isNull();
        assertThat(auditSink.threadName())
                .as("audit must run off the synchronized admission caller")
                .startsWith("direct-stt-forward");

        auditSink.release();

        final RecordedRequest req = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).as("HTTP request should start after audit event is emitted").isNotNull();
        awaitCounter(meters, "attempted", 1.0);
        awaitCounter(meters, "success", 1.0);
    }

    @Test
    void auditFailureBlocksRawAudioForwardButAdmissionStaysAccepted() throws Exception {
        final AudioGatewayAuditSink failingAudit = event -> {
            throw new org.springframework.dao.QueryTimeoutException("audit down");
        };
        final DirectSttForwardingDispatcher dispatcher = dispatcher(
                acceptDelegate(), webClient, props(4), meters, failingAudit);

        final DispatchOutcome out = dispatcher.dispatch(command(new byte[] {1, 2, 3, 4}));

        assertThat(out).isInstanceOf(DispatchOutcome.Accepted.class);
        assertThat(server.takeRequest(750, TimeUnit.MILLISECONDS))
                .as("raw audio must not leave gateway when compute-plane audit fails")
                .isNull();
        awaitCounter(meters, "audit_blocked", 1.0);
        assertThat(counter(meters, "attempted")).isZero();
        awaitInFlightDrained(meters, 4);
    }

    /** Poll a counter up to ~5s for the async callback to land. */
    private static void awaitCounter(final MeterRegistry meters, final String name, final double expected)
            throws InterruptedException {
        final AtomicInteger tries = new AtomicInteger();
        while (counter(meters, name) < expected && tries.incrementAndGet() < 100) {
            Thread.sleep(50);
        }
        assertThat(counter(meters, name))
                .as("metric %s should reach %s", name, expected)
                .isGreaterThanOrEqualTo(expected);
    }

    /** Poll until the in_flight gauge returns to 0 (all permits released) — up to ~5s. */
    private static void awaitInFlightDrained(final MeterRegistry meters, final int maxInFlight)
            throws InterruptedException {
        final AtomicInteger tries = new AtomicInteger();
        while (inFlightGauge(meters) > 0.0 && tries.incrementAndGet() < 100) {
            Thread.sleep(50);
        }
        assertThat(inFlightGauge(meters))
                .as("all in-flight permits should be released")
                .isZero();
    }

    private static double inFlightGauge(final MeterRegistry meters) {
        final var g = meters.find("audio_gateway_direct_stt_in_flight").gauge();
        return g == null ? 0.0 : g.value();
    }

    private static class RecordingAuditSink implements AudioGatewayAuditSink {
        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void emit(final AuditEvent event) {
            events.add(event);
        }

        List<AuditEvent> events() {
            return List.copyOf(events);
        }
    }

    private static final class BlockingAuditSink extends RecordingAuditSink {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<String> threadName = new AtomicReference<>("");

        @Override
        public void emit(final AuditEvent event) {
            threadName.set(Thread.currentThread().getName());
            started.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS))
                        .as("test should release the audit sink")
                        .isTrue();
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
            super.emit(event);
        }

        boolean awaitStarted() throws InterruptedException {
            return started.await(5, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        String threadName() {
            return threadName.get();
        }
    }

    private static final class RecordingTranscriptResultSink implements DirectSttTranscriptResultSink {
        private final List<TranscriptResult> results = new CopyOnWriteArrayList<>();
        private final List<DirectSttTranscriptResultContext> contexts = new CopyOnWriteArrayList<>();

        @Override
        public void emit(final TranscriptResult result, final DirectSttTranscriptResultContext context) {
            results.add(result);
            contexts.add(context);
        }

        List<TranscriptResult> results() {
            return List.copyOf(results);
        }

        List<DirectSttTranscriptResultContext> contexts() {
            return List.copyOf(contexts);
        }
    }
}
