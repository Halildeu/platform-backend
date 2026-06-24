package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.service.AudioChunkDispatcher.ChunkDispatchCommand;
import com.example.audiogateway.service.AudioChunkDispatcher.DispatchOutcome;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        return p;
    }

    private static ChunkDispatchCommand command(final byte[] audio) {
        final AudioChunkPayload payload = AudioChunkPayload.of(audio, "deadbeefcafe0000sha");
        return new ChunkDispatchCommand(
                "SES-abc", 42L, 7L, "22222222-2222-4222-8222-222222222222", "iphone-h-1", "tr",
                AudioFormat.values()[0], 16_000, 1, 0L, 1_000L, "corr-xyz", payload);
    }

    private static double counter(final MeterRegistry meters, final String name) {
        final var c = meters.find("audio_gateway_direct_stt_" + name).counter();
        return c == null ? 0.0 : c.count();
    }

    /** Always-Accepted delegate (NoOp-equivalent) for the success/forward tests. */
    private static AudioChunkDispatcher acceptDelegate() {
        return cmd -> new DispatchOutcome.Accepted();
    }

    @Test
    void forwardsAudioMultipartAndParsesTranscriptOnAccepted() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"merhaba dunya\",\"language\":\"tr\","
                        + "\"language_probability\":0.98,\"duration\":1.2,\"elapsed_ms\":345.6,"
                        + "\"model\":\"large-v3\",\"compute_type\":\"int8\",\"device\":\"cuda\","
                        + "\"segments\":[{\"start\":0.0,\"end\":1.2,\"text\":\"merhaba dunya\"}]}"));

        final DirectSttForwardingDispatcher dispatcher = new DirectSttForwardingDispatcher(
                acceptDelegate(), webClient, props(8), meters);

        final byte[] audio = new byte[] {10, 20, 30, 40, 50};
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
        assertThat(bodyUtf8.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("multipart body must carry the raw audio bytes")
                .contains(new byte[] {10, 20, 30, 40, 50});

        // (3) Success metric increments (await the async callback), no transcript text leaked.
        awaitCounter(meters, "success", 1.0);
        assertThat(counter(meters, "attempted")).isEqualTo(1.0);
        assertThat(counter(meters, "http_error")).isZero();
        assertThat(counter(meters, "exception")).isZero();
    }

    @Test
    void sttErrorDoesNotAffectAdmission() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        final DirectSttForwardingDispatcher dispatcher = new DirectSttForwardingDispatcher(
                acceptDelegate(), webClient, props(8), meters);

        final DispatchOutcome out = dispatcher.dispatch(command(new byte[] {1, 2, 3}));

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
        final AudioChunkDispatcher queueFull = cmd -> new DispatchOutcome.QueueFull(5L);
        final DirectSttForwardingDispatcher dispatcher = new DirectSttForwardingDispatcher(
                queueFull, webClient, props(8), meters);

        final DispatchOutcome out = dispatcher.dispatch(command(new byte[] {9}));

        assertThat(out).isInstanceOf(DispatchOutcome.QueueFull.class);
        assertThat(((DispatchOutcome.QueueFull) out).retryAfterSeconds()).isEqualTo(5L);
        // No request should ever arrive.
        assertThat(server.takeRequest(750, TimeUnit.MILLISECONDS))
                .as("rejected chunk must NOT be forwarded").isNull();
        assertThat(counter(meters, "attempted")).isZero();
    }

    @Test
    void dropsForwardWhenInFlightSaturated() throws Exception {
        // maxInFlight=1 and a slow server: the first forward holds the only permit, the
        // second dispatch on the monitor finds it saturated and drops (admission unaffected).
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"text\":\"x\",\"language\":\"tr\"}")
                .setBodyDelay(1500, TimeUnit.MILLISECONDS));

        final DirectSttForwardingDispatcher dispatcher = new DirectSttForwardingDispatcher(
                acceptDelegate(), webClient, props(1), meters);

        // First chunk: acquires the only permit and starts the (slow) forward.
        final DispatchOutcome first = dispatcher.dispatch(command(new byte[] {1}));
        assertThat(first).isInstanceOf(DispatchOutcome.Accepted.class);
        // Ensure the first forward is in-flight (permit taken) before the second dispatch.
        final RecordedRequest firstReq = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(firstReq).isNotNull();

        // Second chunk while saturated → dropped, but admission still Accepted.
        final DispatchOutcome second = dispatcher.dispatch(command(new byte[] {2}));
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

        final DirectSttForwardingDispatcher dispatcher = new DirectSttForwardingDispatcher(
                acceptDelegate(), webClient, props(4), meters);
        final ChunkDispatchCommand cmd = new ChunkDispatchCommand(
                "SES-1", 1L, 1L, "22222222-2222-4222-8222-222222222222", "dev", "tr",
                AudioFormat.values()[0], 16_000, 1, 0L, 0L, "c", payload);

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
}
