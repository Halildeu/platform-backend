package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Actual TCP provider and registry; mobile disconnect must not own their lifetime. */
class RetainedLiveProviderLoopbackTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DefaultDataBufferFactory buffers = DefaultDataBufferFactory.sharedInstance;

    @Test void thirtySecondDetachReplays264FramesWithoutProviderRestartOrDuplicatePcm() throws Exception {
        final AtomicInteger providerConnections = new AtomicInteger();
        final List<byte[]> providerPcm = new CopyOnWriteArrayList<>();
        final List<DirectSttTranscriptResultContext> persisted = new CopyOnWriteArrayList<>();
        final Sinks.One<Void> firstReceipt = Sinks.one();
        final DisposableServer provider = HttpServer.create().host("127.0.0.1").port(0)
                .route(routes -> routes.ws("/v2/tr", (in, out) -> {
                    providerConnections.incrementAndGet();
                    return out.sendString(in.receiveFrames().map(frame -> {
                        if (frame instanceof BinaryWebSocketFrame binary) {
                            byte[] pcm = new byte[binary.content().readableBytes()];
                            binary.content().readBytes(pcm); providerPcm.add(pcm);
                            return "audio:" + providerPcm.size();
                        }
                        return frame instanceof TextWebSocketFrame text ? text.text() : "";
                    }).concatMap(value -> {
                        if (value.startsWith("audio:")) {
                            int sequence = Integer.parseInt(value.substring(6));
                            Flux<String> receipt = Flux.just("{\"message\":\"AudioAdded\",\"seq_no\":" + sequence + "}");
                            return sequence == 1 ? firstReceipt.asMono().thenMany(Flux.concat(receipt,
                                    Mono.just(providerFinal(0.1, "first final while detached")))) : receipt;
                        }
                        if (value.contains("StartRecognition")) return Flux.just("{\"message\":\"RecognitionStarted\"}");
                        if (value.contains("EndOfStream")) return Flux.just(providerFinal(26.5, "all buffered speech"),
                                "{\"message\":\"EndOfTranscript\"}");
                        return Flux.empty();
                    }));
                })).bindNow();
        AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getSpeechmatics().setRealtimeUrl("ws://127.0.0.1:" + provider.port() + "/v2");
        properties.getDirectStt().getSpeechmatics().setAllowInsecure(true);
        properties.getDirectStt().getSpeechmatics().setApiKey("fixture-only");
        properties.getDirectStt().getSpeechmatics().setAudioAckTimeoutMs(5000);
        InMemoryAudioSessionRegistry registry = new InMemoryAudioSessionRegistry(properties);
        SessionRecord record = ((AudioSessionRegistry.CreateOutcome.Created) registry.create(
                new AudioSessionRegistry.SessionCreateCommand(1L, 4L, "meeting-1", "device-1", "tr",
                        "speechmatics", "realtime", List.of(), AudioFormat.PCM16, 16000, 1,
                        "retained-loopback-idempotency", System.currentTimeMillis()))).record();
        ReactorNettyWebSocketClient providerClient = new ReactorNettyWebSocketClient();
        LiveSttWebSocketProxyHandler handler = new LiveSttWebSocketProxyHandler(registry, properties,
                mock(AudioGatewayAuditSink.class), (result, context) -> { persisted.add(context); return "fixture-" + persisted.size(); },
                providerClient, providerClient, mapper, new SimpleMeterRegistry());
        Probe first = null;
        Probe resumed = null;
        try {
            first = connect(handler, record.sessionId(), "", 4L);
            final Probe original = first;
            await(() -> original.events.stream().anyMatch(event -> event.contains("resume_complete")));
            String ready = first.events.stream().filter(event -> event.contains("\"type\":\"ready\"")).findFirst().orElseThrow();
            String epoch = mapper.readTree(ready).path("source_epoch").asText();
            first.input.emitNext(binary(0), Sinks.EmitFailureHandler.FAIL_FAST);
            await(() -> providerPcm.size() == 1);
            first.subscription.dispose();
            firstReceipt.tryEmitEmpty();
            // Persistence happens with no physical client subscription remaining.
            await(() -> persisted.size() == 1);
            assertThat(first.events).noneMatch(event -> event.contains("audio_ack") || event.contains("\"type\":\"final\""));
            Mono.delay(Duration.ofSeconds(30)).block();
            // A different owner cannot take over the retained source.
            Probe intruder = connect(handler, record.sessionId(), "&source_epoch=" + epoch + "&after_final=-1", 99L);
            assertThat(intruder.closed.get()).isEqualTo(CloseStatus.POLICY_VIOLATION);
            intruder.subscription.dispose();
            resumed = connect(handler, record.sessionId(), "&source_epoch=" + epoch + "&after_final=-1", 4L);
            final Probe current = resumed;
            await(() -> current.events.stream().anyMatch(event -> event.contains("resume_complete")));
            assertThat(current.events.stream().filter(event -> event.contains("\"type\":\"final\""))).hasSize(1);
            // Lost ACK: canonical zero is retried, but provider must receive it only once.
            resumed.input.emitNext(binary(0), Sinks.EmitFailureHandler.FAIL_FAST);
            for (int seq = 1; seq <= 264; seq++) resumed.input.emitNext(binary(seq), Sinks.EmitFailureHandler.FAIL_FAST);
            await(() -> current.events.contains("{\"type\":\"audio_ack\",\"chunk_seq\":264}"));
            assertThat(providerPcm).hasSize(265);
            assertThat(providerConnections).hasValue(1);
            for (int seq = 0; seq <= 264; seq++) assertThat(providerPcm.get(seq)).isEqualTo(pcm(seq));
            resumed.input.emitNext(text("{\"type\":\"eof\"}"), Sinks.EmitFailureHandler.FAIL_FAST);
            await(() -> current.events.contains("{\"type\":\"drained\"}"));
            assertThat(persisted).hasSize(2);
            assertThat(persisted.get(1).transportEpoch()).isEqualTo(persisted.get(0).transportEpoch());
            assertThat(persisted.stream().map(DirectSttTranscriptResultContext::windowSeq)).containsExactly(0L, 1L);
            assertThat(persisted.get(0).firstChunkSeq()).isZero();
            assertThat(persisted.get(0).lastChunkSeq()).isZero();
            assertThat(persisted.get(1).firstChunkSeq()).isEqualTo(1);
            assertThat(persisted.get(1).lastChunkSeq()).isEqualTo(264);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int seq = 1; seq <= 264; seq++) digest.update(pcm(seq));
            assertThat(persisted.get(1).sha256()).isEqualTo("sha256:" + HexFormat.of().formatHex(digest.digest()));
            assertThat(current.events.stream().filter(event -> event.contains("\"type\":\"final\""))).hasSize(2);
            assertThat(current.events.indexOf("{\"type\":\"eof_ack\"}"))
                    .isLessThan(current.events.indexOf("{\"type\":\"drained\"}"));
            assertThat(current.events).noneMatch(event -> event.contains("\"type\":\"error\""));
        } finally {
            if (first != null) first.subscription.dispose();
            if (resumed != null) resumed.subscription.dispose();
            handler.destroy();
            provider.disposeNow(Duration.ofSeconds(5));
        }
    }

    @Test void concurrentLegacyAndRetainedAdmissionStartsOnlyOneProvider() throws Exception {
        try (var threads = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 12; round++) {
                AudioGatewayProperties properties = new AudioGatewayProperties();
                properties.getDirectStt().getSpeechmatics().setApiKey("fixture");
                InMemoryAudioSessionRegistry registry = new InMemoryAudioSessionRegistry(properties);
                SessionRecord record = ((AudioSessionRegistry.CreateOutcome.Created) registry.create(
                        new AudioSessionRegistry.SessionCreateCommand(1L, 4L, "meeting", "device", "tr",
                                "speechmatics", "realtime", List.of(), AudioFormat.PCM16, 16000, 1,
                                "concurrent-mode-reservation", System.currentTimeMillis()))).record();
                final AtomicInteger connections = new AtomicInteger();
                var provider = mock(org.springframework.web.reactive.socket.client.WebSocketClient.class);
                when(provider.execute(any(URI.class), any(HttpHeaders.class), any(org.springframework.web.reactive.socket.WebSocketHandler.class)))
                        .thenReturn(Mono.<Void>never().doOnSubscribe(ignored -> connections.incrementAndGet()));
                LiveSttWebSocketProxyHandler handler = new LiveSttWebSocketProxyHandler(registry, properties,
                        mock(AudioGatewayAuditSink.class), DirectSttTranscriptResultSink.noop(), provider, provider,
                        mapper, new SimpleMeterRegistry());
                var barrier = new java.util.concurrent.CyclicBarrier(2);
                var legacyFuture = threads.submit(() -> { barrier.await(); return connect(handler, record.sessionId(), "", 4L, false); });
                var retainedFuture = threads.submit(() -> { barrier.await(); return connect(handler, record.sessionId(), "", 4L, true); });
                Probe legacy = legacyFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                Probe retained = retainedFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                try {
                    assertThat(connections).hasValue(1);
                    assertThat(List.of(legacy, retained).stream().filter(probe -> CloseStatus.POLICY_VIOLATION.equals(probe.closed.get())))
                            .hasSize(1);
                } finally { legacy.subscription.dispose(); retained.subscription.dispose(); handler.destroy(); }
            }
        }
    }

    private String providerFinal(double endTime, String content) {
        return "{\"message\":\"AddTranscript\",\"metadata\":{\"end_time\":" + endTime
                + ",\"transcript\":\"" + content + "\"}}";
    }
    private Probe connect(LiveSttWebSocketProxyHandler handler, String id, String suffix, long user) {
        return connect(handler, id, suffix, user, true);
    }
    private Probe connect(LiveSttWebSocketProxyHandler handler, String id, String suffix, long user, boolean negotiated) {
        WebSocketSession client = mock(WebSocketSession.class);
        Sinks.Many<WebSocketMessage> input = Sinks.many().unicast().onBackpressureBuffer();
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicReference<CloseStatus> closed = new AtomicReference<>();
        Jwt jwt = Jwt.withTokenValue("fixture").header("alg", "none").subject("fixture")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("companyId", 1L).claim("userId", user).build();
        when(client.getHandshakeInfo()).thenReturn(new HandshakeInfo(URI.create(
                "ws://gateway/api/v1/audio-gateway/sessions/" + id + "/stream" + (negotiated ? "?resume_protocol=recording-resume-v1" : "") + suffix),
                new HttpHeaders(), Mono.just(new JwtAuthenticationToken(jwt)), null));
        when(client.receive()).thenReturn(input.asFlux());
        when(client.textMessage(anyString())).thenAnswer(call -> text(call.getArgument(0)));
        when(client.send(any(Publisher.class))).thenAnswer(call -> Flux.from(call.<Publisher<WebSocketMessage>>getArgument(0))
                .doOnNext(value -> events.add(value.getPayloadAsText())).then());
        when(client.close(any(CloseStatus.class))).thenAnswer(call -> { closed.set(call.getArgument(0)); return Mono.empty(); });
        Disposable subscription = handler.handle(client).subscribe();
        return new Probe(input, events, closed, subscription);
    }
    private static byte[] pcm(int seq) {
        byte[] pcm = new byte[3200]; Arrays.fill(pcm, (byte) (seq % 127)); return pcm;
    }
    private WebSocketMessage binary(int seq) {
        byte[] pcm = pcm(seq);
        byte[] frame = ByteBuffer.allocate(19 + pcm.length).order(ByteOrder.BIG_ENDIAN)
                .put((byte) 1).putLong(seq).putLong(1000L + seq * 100).putShort((short) pcm.length).put(pcm).array();
        return new WebSocketMessage(WebSocketMessage.Type.BINARY, buffers.wrap(frame));
    }
    private WebSocketMessage text(String value) {
        return new WebSocketMessage(WebSocketMessage.Type.TEXT, buffers.wrap(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(10);
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) Mono.delay(Duration.ofMillis(10)).block();
        assertThat(condition.getAsBoolean()).as("loopback condition before deadline").isTrue();
    }
    private record Probe(Sinks.Many<WebSocketMessage> input, List<String> events,
            AtomicReference<CloseStatus> closed, Disposable subscription) { }
}
