package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioChunkPayload;
import com.example.audiogateway.dto.AudioFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
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

/**
 * Real reactor-netty client + server loopback regression guard for the Faz 24 #184
 * WebSocket bridge double-release bug.
 *
 * <p>The old {@code bridge()} code called {@code DataBufferUtils.release(...)} on the
 * inbound frame payload inside both {@code handle} blocks. reactor-netty owns those
 * inbound WebSocket frames and releases them itself once the handler returns
 * ({@code FluxReceive.drainReceiver -> DefaultByteBufHolder.release}); the extra
 * release drops the shared {@code ByteBuf} refCnt to 0 and the framework's own
 * release then throws {@code io.netty.util.IllegalReferenceCountException:
 * refCnt: 0, decrement: 1}. The bridge therefore collapsed after the very first
 * relayed frame ({@code upstream_failures_total++}, client closed
 * {@code SERVER_ERROR}).
 *
 * <p>This test drives a genuine reactor-netty client ({@link ReactorNettyWebSocketClient})
 * against a genuine reactor-netty {@link HttpServer} WebSocket endpoint, so the
 * upstream leg produces framework-owned inbound frames whose lifecycle is exactly
 * the one that crashes under the old code. Codex verdict (session 019f4c...):
 * an EmbeddedChannel / leak-detector harness would be fake-green here; only a real
 * loopback exercises the double-release path.
 *
 * <p><b>Regression contract:</b> GREEN with the fix, DETERMINISTICALLY RED without
 * it. Reverting the two {@code handle} blocks to the release-ful form makes the
 * first upstream event double-release, which flips {@code upstream_failures_total}
 * to 1, closes the client with {@code SERVER_ERROR}, and drops the relayed-event
 * count below the frame count — so every one of the assertions below fails.
 */
class LiveSttWebSocketProxyHandlerLoopbackTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String READY_EVENT = "{\"type\":\"ready\",\"sample_rate\":16000,"
            + "\"live_model\":\"fixture-live\",\"final_model\":\"fixture-final\","
            + "\"partial_mode\":\"stable-v1\",\"protocol\":\"source-ranges-v1\","
            + "\"capabilities\":[\"eof\",\"source-ranges-v1\"],\"supports_eof\":true,"
            + "\"terminal_timeout_ms\":60000}";

    private static String readyEvent(final long terminalTimeoutMs) {
        return READY_EVENT.replace(
                "\"terminal_timeout_ms\":60000",
                "\"terminal_timeout_ms\":" + terminalTimeoutMs);
    }

    private AudioSessionRegistry sessions;
    private AudioGatewayAuditSink auditSink;
    private SimpleMeterRegistry meters;
    private ReactorNettyWebSocketClient upstreamClient;
    private DisposableServer upstreamServer;
    private Logger handlerLogger;
    private ListAppender<ILoggingEvent> warnAppender;
    private Disposable handleSubscription;
    private AtomicLong lastRelayedLiveSequence;

    @BeforeEach
    void setUp() {
        sessions = mock(AudioSessionRegistry.class);
        auditSink = mock(AudioGatewayAuditSink.class);
        meters = new SimpleMeterRegistry();
        upstreamClient = new ReactorNettyWebSocketClient();
        lastRelayedLiveSequence = new AtomicLong(-1L);
        when(sessions.admitLiveFrame(any(), any())).thenAnswer(invocation -> {
            final AudioSessionRegistry.LiveFrameCommand command = invocation.getArgument(0);
            final AudioSessionRegistry.LiveFrameCommit commit = invocation.getArgument(1);
            final long previous = lastRelayedLiveSequence.get();
            if (command.chunkSeq() <= previous) {
                return new AudioSessionRegistry.LiveFrameOutcome.Duplicate(previous);
            }
            if (command.chunkSeq() != previous + 1L) {
                return new AudioSessionRegistry.LiveFrameOutcome.Gap(
                        previous + 1L, command.chunkSeq());
            }
            final SessionRecord current = streamingSession(1L, 4L);
            commit.beforeCommit(current);
            lastRelayedLiveSequence.set(command.chunkSeq());
            return new AudioSessionRegistry.LiveFrameOutcome.Accepted(command.chunkSeq());
        });
        handlerLogger = (Logger) LoggerFactory.getLogger(LiveSttWebSocketProxyHandler.class);
        warnAppender = new ListAppender<>();
        warnAppender.start();
        handlerLogger.addAppender(warnAppender);
    }

    @AfterEach
    void tearDown() {
        if (handleSubscription != null) {
            handleSubscription.dispose();
        }
        if (handlerLogger != null && warnAppender != null) {
            handlerLogger.detachAppender(warnAppender);
        }
        if (upstreamServer != null) {
            upstreamServer.disposeNow(Duration.ofSeconds(5));
        }
    }

    @Test
    void relaysEveryFrameOnOneConnectionWithoutReactorNettyDoubleRelease() {
        final int frameCount = 3;

        // Genuine reactor-netty upstream: every inbound binary frame is answered with a
        // TEXT event. Those TEXT frames flow back through the gateway's real
        // upstream.receive() -> the exact framework-owned inbound frame lifecycle that
        // the old release-ful bridge double-releases.
        final AtomicInteger upstreamReceived = new AtomicInteger();
        upstreamServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.ws("/ws/stream", (in, out) ->
                        out.sendString(Flux.concat(
                                Flux.just(READY_EVENT),
                                in.receiveFrames()
                                        .ofType(BinaryWebSocketFrame.class)
                                        .doOnNext(frame -> upstreamReceived.incrementAndGet())
                                        .map(frame -> "{\"type\":\"partial\",\"partial\":\"ok\"}")))))
                .bindNow();

        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl(
                "ws://127.0.0.1:" + upstreamServer.port() + "/ws/stream");

        final LiveSttWebSocketProxyHandler handler = new LiveSttWebSocketProxyHandler(
                sessions, properties, auditSink, DirectSttTranscriptResultSink.noop(),
                upstreamClient, new ObjectMapper(), meters);

        when(sessions.get("session-1")).thenReturn(Optional.of(streamingSession(1L, 4L)));

        final NettyDataBufferFactory clientFactory =
                new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT);

        // Controlled client inbound: emit N valid frames and stay open, so the bridge is
        // not torn down by an early upstream.send() completion — the download leg then has
        // to survive every relayed upstream event, not just the first.
        final Sinks.Many<WebSocketMessage> clientInbound =
                Sinks.many().unicast().onBackpressureBuffer();
        final Flux<WebSocketMessage> clientInboundFlux = clientInbound.asFlux();

        final List<WebSocketMessage> relayedToClient = new CopyOnWriteArrayList<>();
        final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();

        final WebSocketSession client = mock(WebSocketSession.class);
        when(client.getHandshakeInfo()).thenReturn(new HandshakeInfo(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/session-1/stream"),
                new HttpHeaders(),
                Mono.just(ownerJwt()),
                null));
        when(client.receive()).thenReturn(clientInboundFlux);
        when(client.textMessage(anyString())).thenAnswer(invocation ->
                new WebSocketMessage(WebSocketMessage.Type.TEXT,
                        clientFactory.wrap(invocation.<String>getArgument(0)
                                .getBytes(StandardCharsets.UTF_8))));
        // client.send(...) must actually subscribe to the relay publisher (drives
        // upstream.receive()); Mono.never() would never exercise the crashing leg.
        when(client.send(any(Publisher.class))).thenAnswer(invocation ->
                Flux.from(invocation.<Publisher<WebSocketMessage>>getArgument(0))
                        .doOnNext(relayedToClient::add)
                        .then());
        when(client.close(any(CloseStatus.class))).thenAnswer(invocation -> {
            closeStatus.set(invocation.getArgument(0));
            return Mono.empty();
        });

        handleSubscription = handler.handle(client)
                .subscribe(ignored -> { }, error -> { }, () -> { });

        for (int seq = 0; seq < frameCount; seq++) {
            clientInbound.emitNext(binaryFrame(clientFactory, seq), Sinks.EmitFailureHandler.FAIL_FAST);
        }

        // Settle: success = all frames relayed; failure = the bridge closed the client.
        final Instant deadline = Instant.now().plus(TEST_TIMEOUT);
        while (Instant.now().isBefore(deadline)
                && relayedToClient.size() < frameCount + 1
                && closeStatus.get() == null) {
            sleepQuietly();
        }

        final double accepted = meters
                .counter("audio_gateway_live_stream_frames_total", "outcome", "accepted").count();
        final double upstreamFailures = meters
                .counter("audio_gateway_live_stream_upstream_failures_total").count();

        // (f) The double-release surfaces as an upstream failure — it must stay 0.
        //     On the release-ful bug the captured warn shows err=IllegalReferenceCountException.
        assertThat(upstreamFailures)
                .as("upstream_failures_total must stay 0 (bridge warns: %s)", warnMessages())
                .isZero();
        // (e) The connection is NOT torn down after the first frame.
        verify(client, never()).close(CloseStatus.SERVER_ERROR);
        assertThat(closeStatus.get())
                .as("client must not be closed on the happy path (bridge warns: %s)", warnMessages())
                .isNull();
        // (b) Every binary frame on the SAME connection is accepted.
        assertThat(accepted)
                .as("accepted frames on one connection (bridge warns: %s)", warnMessages())
                .isEqualTo((double) frameCount);
        // (c) The real upstream server received every forwarded frame.
        assertThat(upstreamReceived.get())
                .as("frames received by the loopback upstream server")
                .isEqualTo(frameCount);
        // (d) Every upstream event was relayed back to the client.
        assertThat(relayedToClient)
                .as("events relayed to the client (bridge warns: %s)", warnMessages())
                .hasSize(frameCount + 1);
    }

    @Test
    void restAcceptedSequenceIsRelayedExactlyOnceOverWebSocket() {
        final AtomicInteger upstreamReceived = new AtomicInteger();
        upstreamServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.ws("/ws/stream", (in, out) ->
                        out.sendString(Flux.concat(
                                Flux.just(READY_EVENT),
                                in.receiveFrames()
                                        .ofType(BinaryWebSocketFrame.class)
                                        .doOnNext(frame -> upstreamReceived.incrementAndGet())
                                        .map(frame -> "{\"type\":\"partial\"}")))))
                .bindNow();

        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getBounds().setMaxActiveSessions(4);
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl(
                "ws://127.0.0.1:" + upstreamServer.port() + "/ws/stream");
        final InMemoryAudioSessionRegistry realRegistry =
                new InMemoryAudioSessionRegistry(properties);
        final SessionRecord session = ((AudioSessionRegistry.CreateOutcome.Created)
                realRegistry.create(new AudioSessionRegistry.SessionCreateCommand(
                        1L, 4L, "meeting-1", "desktop-1", "tr",
                        AudioFormat.PCM16, 16_000, 1,
                        "start-idempotency-key-rest-live-0001", 1_000L))).record();
        assertThat(realRegistry.admitChunk(
                        new AudioSessionRegistry.ChunkRecordCommand(
                                session.sessionId(), session.tenantId(), session.userId(),
                                "rest-idempotency-key-live-0001", 0L, 2_000L,
                                "correlation-rest-live",
                                AudioChunkPayload.of(new byte[]{1, 2}, "sha256:rest-live-0"),
                                2_001L),
                        command -> new AudioChunkDispatcher.DispatchOutcome.Accepted()))
                .isInstanceOf(AudioSessionRegistry.ChunkOutcome.Accepted.class);

        final LiveSttWebSocketProxyHandler handler = new LiveSttWebSocketProxyHandler(
                realRegistry, properties, auditSink, DirectSttTranscriptResultSink.noop(),
                upstreamClient, new ObjectMapper(), meters);
        final NettyDataBufferFactory clientFactory =
                new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT);
        final Sinks.Many<WebSocketMessage> clientInbound =
                Sinks.many().unicast().onBackpressureBuffer();
        final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        final WebSocketSession client = mock(WebSocketSession.class);
        when(client.getHandshakeInfo()).thenReturn(new HandshakeInfo(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/"
                        + session.sessionId() + "/stream"),
                new HttpHeaders(), Mono.just(ownerJwt()), null));
        when(client.receive()).thenReturn(clientInbound.asFlux());
        when(client.textMessage(anyString())).thenAnswer(invocation ->
                textFrame(clientFactory, invocation.getArgument(0)));
        when(client.send(any(Publisher.class))).thenAnswer(invocation ->
                Flux.from(invocation.<Publisher<WebSocketMessage>>getArgument(0)).then());
        when(client.close(any(CloseStatus.class))).thenAnswer(invocation -> {
            closeStatus.set(invocation.getArgument(0));
            return Mono.empty();
        });

        handleSubscription = handler.handle(client).subscribe();
        clientInbound.emitNext(binaryFrame(clientFactory, 0L), Sinks.EmitFailureHandler.FAIL_FAST);
        clientInbound.emitNext(binaryFrame(clientFactory, 0L), Sinks.EmitFailureHandler.FAIL_FAST);

        final Instant deadline = Instant.now().plus(TEST_TIMEOUT);
        while (Instant.now().isBefore(deadline)
                && (upstreamReceived.get() < 1
                || meters.counter(
                        "audio_gateway_live_stream_frames_total", "outcome", "accepted").count() < 1
                || meters.counter(
                        "audio_gateway_live_stream_frames_total", "outcome", "duplicate").count() < 1)
                && closeStatus.get() == null) {
            sleepQuietly();
        }

        assertThat(upstreamReceived).hasValue(1);
        assertThat(meters.counter(
                        "audio_gateway_live_stream_frames_total", "outcome", "accepted").count())
                .isEqualTo(1.0d);
        assertThat(meters.counter(
                        "audio_gateway_live_stream_frames_total", "outcome", "duplicate").count())
                .isEqualTo(1.0d);
        assertThat(realRegistry.get(session.sessionId()).orElseThrow().lastAcceptedChunkSeq())
                .isEqualTo(0L);
        assertThat(closeStatus.get()).isNull();
    }

    @Test
    void waitsForCanonicalReadyBeforeAdmittingOrForwardingClientAudio() {
        final AtomicInteger upstreamReceived = new AtomicInteger();
        upstreamServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.ws("/ws/stream", (in, out) ->
                        out.sendString(Flux.concat(
                                Mono.delay(Duration.ofMillis(300L)).map(ignored -> READY_EVENT),
                                in.receiveFrames()
                                        .ofType(BinaryWebSocketFrame.class)
                                        .doOnNext(frame -> upstreamReceived.incrementAndGet())
                                        .map(frame -> "{\"type\":\"partial\"}")))))
                .bindNow();

        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl(
                "ws://127.0.0.1:" + upstreamServer.port() + "/ws/stream");
        final LiveSttWebSocketProxyHandler handler = new LiveSttWebSocketProxyHandler(
                sessions, properties, auditSink, DirectSttTranscriptResultSink.noop(),
                upstreamClient, new ObjectMapper(), meters);
        when(sessions.get("session-1")).thenReturn(Optional.of(streamingSession(1L, 4L)));

        final NettyDataBufferFactory clientFactory =
                new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT);
        final Sinks.Many<WebSocketMessage> clientInbound =
                Sinks.many().unicast().onBackpressureBuffer();
        final WebSocketSession client = mock(WebSocketSession.class);
        when(client.getHandshakeInfo()).thenReturn(new HandshakeInfo(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/session-1/stream"),
                new HttpHeaders(), Mono.just(ownerJwt()), null));
        when(client.receive()).thenReturn(clientInbound.asFlux());
        when(client.textMessage(anyString())).thenAnswer(invocation ->
                textFrame(clientFactory, invocation.getArgument(0)));
        when(client.send(any(Publisher.class))).thenAnswer(invocation ->
                Flux.from(invocation.<Publisher<WebSocketMessage>>getArgument(0)).then());
        when(client.close(any(CloseStatus.class))).thenReturn(Mono.empty());

        handleSubscription = handler.handle(client).subscribe();
        clientInbound.emitNext(binaryFrame(clientFactory, 0L), Sinks.EmitFailureHandler.FAIL_FAST);

        try {
            Thread.sleep(150L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        assertThat(lastRelayedLiveSequence).hasValue(-1L);
        assertThat(upstreamReceived).hasValue(0);

        final Instant deadline = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(deadline) && upstreamReceived.get() == 0) {
            sleepQuietly();
        }
        assertThat(lastRelayedLiveSequence).hasValue(0L);
        assertThat(upstreamReceived).hasValue(1);
    }

    @Test
    void eofWaitsForAndRelaysAckFinalAndDrainedBeforeBridgeCompletes() {
        final AtomicBoolean eofReceived = new AtomicBoolean();
        upstreamServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.ws("/ws/stream", (in, out) ->
                        out.sendString(Flux.concat(
                                Flux.just(READY_EVENT),
                                in.receiveFrames().concatMap(frame -> {
                                    if (frame instanceof BinaryWebSocketFrame) {
                                        return Flux.just("{\"type\":\"partial\"}");
                                    }
                                    if (frame instanceof TextWebSocketFrame text
                                            && LiveStreamControlFrame.CANONICAL_EOF.equals(text.text())) {
                                        eofReceived.set(true);
                                        return Flux.just(
                                                "{\"type\":\"eof_ack\"}",
                                                "{\"type\":\"final\",\"seq\":7,\"text\":\"fixture\","
                                                        + "\"reason\":\"eof\",\"elapsed_ms\":42,"
                                                        + "\"source_start_sample\":0,"
                                                        + "\"source_end_sample\":2}",
                                                "{\"type\":\"drained\"}");
                                    }
                                    return Flux.empty();
                                })))))
                .bindNow();

        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl(
                "ws://127.0.0.1:" + upstreamServer.port() + "/ws/stream");
        final AtomicReference<com.example.audiogateway.dto.TranscriptResult> persistedResult =
                new AtomicReference<>();
        final AtomicReference<DirectSttTranscriptResultContext> persistedContext =
                new AtomicReference<>();
        final DirectSttTranscriptResultSink resultSink = (result, context) -> {
            persistedResult.set(result);
            persistedContext.set(context);
        };
        final LiveSttWebSocketProxyHandler handler = new LiveSttWebSocketProxyHandler(
                sessions, properties, auditSink, resultSink,
                upstreamClient, new ObjectMapper(), meters);
        when(sessions.get("session-1")).thenReturn(Optional.of(streamingSession(1L, 4L)));

        final NettyDataBufferFactory clientFactory =
                new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT);
        final Sinks.Many<WebSocketMessage> clientInbound =
                Sinks.many().unicast().onBackpressureBuffer();
        final List<String> relayedText = new CopyOnWriteArrayList<>();
        final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean finalObservedAfterPersistence = new AtomicBoolean();

        final WebSocketSession client = mock(WebSocketSession.class);
        when(client.getHandshakeInfo()).thenReturn(new HandshakeInfo(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/session-1/stream"),
                new HttpHeaders(), Mono.just(ownerJwt()), null));
        when(client.receive()).thenReturn(clientInbound.asFlux());
        when(client.textMessage(anyString())).thenAnswer(invocation ->
                textFrame(clientFactory, invocation.getArgument(0)));
        when(client.send(any(Publisher.class))).thenAnswer(invocation ->
                Flux.from(invocation.<Publisher<WebSocketMessage>>getArgument(0))
                        .doOnNext(message -> {
                            final String text = message.getPayloadAsText();
                            if (text.contains("\"type\":\"final\"")) {
                                finalObservedAfterPersistence.set(persistedResult.get() != null);
                            }
                            relayedText.add(text);
                        })
                        .then());
        when(client.close(any(CloseStatus.class))).thenAnswer(invocation -> {
            closeStatus.set(invocation.getArgument(0));
            return Mono.empty();
        });

        handleSubscription = handler.handle(client)
                .subscribe(ignored -> { }, error -> { }, () -> completed.set(true));
        clientInbound.emitNext(binaryFrame(clientFactory, 0L), Sinks.EmitFailureHandler.FAIL_FAST);
        clientInbound.emitNext(
                textFrame(clientFactory, " { \"type\" : \"eof\" } "),
                Sinks.EmitFailureHandler.FAIL_FAST);

        final Instant deadline = Instant.now().plus(TEST_TIMEOUT);
        while (Instant.now().isBefore(deadline) && !completed.get() && closeStatus.get() == null) {
            sleepQuietly();
        }

        assertThat(eofReceived).isTrue();
        assertThat(relayedText).containsExactly(
                READY_EVENT,
                "{\"type\":\"partial\"}",
                "{\"type\":\"eof_ack\"}",
                "{\"type\":\"final\",\"seq\":7,\"text\":\"fixture\","
                        + "\"reason\":\"eof\",\"elapsed_ms\":42,"
                        + "\"source_start_sample\":0,\"source_end_sample\":2}",
                "{\"type\":\"drained\"}");
        assertThat(persistedResult.get()).isNotNull();
        assertThat(persistedResult.get().text()).isEqualTo("fixture");
        assertThat(persistedContext.get()).isNotNull();
        assertThat(persistedContext.get().windowSeq()).isEqualTo(7L);
        assertThat(persistedContext.get().firstChunkSeq()).isZero();
        assertThat(persistedContext.get().lastChunkSeq()).isZero();
        assertThat(persistedContext.get().flushReason()).isEqualTo("stream_eof");
        assertThat(persistedContext.get().sha256()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(finalObservedAfterPersistence)
                .as("client final is relayed only after durable transcript handoff")
                .isTrue();
        assertThat(completed).isTrue();
        assertThat(closeStatus.get()).isNull();
        assertThat(meters.counter("audio_gateway_live_stream_upstream_failures_total").count())
                .isZero();
    }

    @Test
    void drainedBeforeEofAckFailsClosedWithoutRelayingFalseSuccess() {
        upstreamServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.ws("/ws/stream", (in, out) ->
                        out.sendString(Flux.concat(
                                Flux.just(READY_EVENT),
                                in.receiveFrames().concatMap(frame -> {
                                    if (frame instanceof TextWebSocketFrame text
                                            && LiveStreamControlFrame.CANONICAL_EOF.equals(text.text())) {
                                        return Flux.just("{\"type\":\"drained\"}");
                                    }
                                    return Flux.empty();
                                })))))
                .bindNow();

        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl(
                "ws://127.0.0.1:" + upstreamServer.port() + "/ws/stream");
        final LiveSttWebSocketProxyHandler handler = new LiveSttWebSocketProxyHandler(
                sessions, properties, auditSink, DirectSttTranscriptResultSink.noop(),
                upstreamClient, new ObjectMapper(), meters);
        when(sessions.get("session-1")).thenReturn(Optional.of(streamingSession(1L, 4L)));

        final NettyDataBufferFactory clientFactory =
                new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT);
        final Sinks.Many<WebSocketMessage> clientInbound =
                Sinks.many().unicast().onBackpressureBuffer();
        final List<String> relayedText = new CopyOnWriteArrayList<>();
        final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        final WebSocketSession client = mock(WebSocketSession.class);
        when(client.getHandshakeInfo()).thenReturn(new HandshakeInfo(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/session-1/stream"),
                new HttpHeaders(), Mono.just(ownerJwt()), null));
        when(client.receive()).thenReturn(clientInbound.asFlux());
        when(client.textMessage(anyString())).thenAnswer(invocation ->
                textFrame(clientFactory, invocation.getArgument(0)));
        when(client.send(any(Publisher.class))).thenAnswer(invocation ->
                Flux.from(invocation.<Publisher<WebSocketMessage>>getArgument(0))
                        .doOnNext(message -> relayedText.add(message.getPayloadAsText()))
                        .then());
        when(client.close(any(CloseStatus.class))).thenAnswer(invocation -> {
            closeStatus.set(invocation.getArgument(0));
            return Mono.empty();
        });

        handleSubscription = handler.handle(client).subscribe();
        clientInbound.emitNext(
                textFrame(clientFactory, "{\"type\":\"eof\"}"),
                Sinks.EmitFailureHandler.FAIL_FAST);

        final Instant deadline = Instant.now().plus(TEST_TIMEOUT);
        while (Instant.now().isBefore(deadline) && closeStatus.get() == null) {
            sleepQuietly();
        }

        assertThat(closeStatus.get()).isEqualTo(CloseStatus.SERVER_ERROR);
        assertThat(relayedText).containsExactly(READY_EVENT);
        assertThat(meters.counter("audio_gateway_live_stream_upstream_failures_total").count())
                .isEqualTo(1.0d);
    }

    @Test
    void unknownUpstreamEventAfterEofAckFailsClosedWithoutRelayingDrained() {
        upstreamServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.ws("/ws/stream", (in, out) ->
                        out.sendString(Flux.concat(
                                Flux.just(READY_EVENT),
                                in.receiveFrames().concatMap(frame -> {
                                    if (frame instanceof TextWebSocketFrame text
                                            && LiveStreamControlFrame.CANONICAL_EOF.equals(text.text())) {
                                        return Flux.just(
                                                "{\"type\":\"eof_ack\"}",
                                                "{\"type\":\"telemetry\"}",
                                                "{\"type\":\"drained\"}");
                                    }
                                    return Flux.empty();
                                })))))
                .bindNow();

        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl(
                "ws://127.0.0.1:" + upstreamServer.port() + "/ws/stream");
        final LiveSttWebSocketProxyHandler handler = new LiveSttWebSocketProxyHandler(
                sessions, properties, auditSink, DirectSttTranscriptResultSink.noop(),
                upstreamClient, new ObjectMapper(), meters);
        when(sessions.get("session-1")).thenReturn(Optional.of(streamingSession(1L, 4L)));

        final NettyDataBufferFactory clientFactory =
                new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT);
        final Sinks.Many<WebSocketMessage> clientInbound =
                Sinks.many().unicast().onBackpressureBuffer();
        final List<String> relayedText = new CopyOnWriteArrayList<>();
        final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        final WebSocketSession client = mock(WebSocketSession.class);
        when(client.getHandshakeInfo()).thenReturn(new HandshakeInfo(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/session-1/stream"),
                new HttpHeaders(), Mono.just(ownerJwt()), null));
        when(client.receive()).thenReturn(clientInbound.asFlux());
        when(client.textMessage(anyString())).thenAnswer(invocation ->
                textFrame(clientFactory, invocation.getArgument(0)));
        when(client.send(any(Publisher.class))).thenAnswer(invocation ->
                Flux.from(invocation.<Publisher<WebSocketMessage>>getArgument(0))
                        .doOnNext(message -> relayedText.add(message.getPayloadAsText()))
                        .then());
        when(client.close(any(CloseStatus.class))).thenAnswer(invocation -> {
            closeStatus.set(invocation.getArgument(0));
            return Mono.empty();
        });

        handleSubscription = handler.handle(client).subscribe();
        clientInbound.emitNext(
                textFrame(clientFactory, "{\"type\":\"eof\"}"),
                Sinks.EmitFailureHandler.FAIL_FAST);

        final Instant deadline = Instant.now().plus(TEST_TIMEOUT);
        while (Instant.now().isBefore(deadline) && closeStatus.get() == null) {
            sleepQuietly();
        }

        assertThat(closeStatus.get()).isEqualTo(CloseStatus.SERVER_ERROR);
        assertThat(relayedText).containsExactly(READY_EVENT, "{\"type\":\"eof_ack\"}");
        assertThat(relayedText).doesNotContain(
                "{\"type\":\"telemetry\"}", "{\"type\":\"drained\"}");
    }

    @Test
    void transcriptPersistenceFailureClosesBridgeWithoutRelayingFalseFinal() {
        upstreamServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.ws("/ws/stream", (in, out) ->
                        out.sendString(Flux.concat(
                                Flux.just(READY_EVENT),
                                in.receiveFrames()
                                        .ofType(BinaryWebSocketFrame.class)
                                        .map(frame -> "{\"type\":\"final\",\"seq\":0,"
                                                + "\"text\":\"must-not-leak\","
                                                + "\"reason\":\"window\",\"elapsed_ms\":12,"
                                                + "\"source_start_sample\":0,"
                                                + "\"source_end_sample\":2}")))))
                .bindNow();

        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl(
                "ws://127.0.0.1:" + upstreamServer.port() + "/ws/stream");
        final DirectSttTranscriptResultSink failingSink = (result, context) -> {
            throw new IllegalStateException("fixture sink unavailable");
        };
        final LiveSttWebSocketProxyHandler handler = new LiveSttWebSocketProxyHandler(
                sessions, properties, auditSink, failingSink,
                upstreamClient, new ObjectMapper(), meters);
        when(sessions.get("session-1")).thenReturn(Optional.of(streamingSession(1L, 4L)));

        final NettyDataBufferFactory clientFactory =
                new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT);
        final Sinks.Many<WebSocketMessage> clientInbound =
                Sinks.many().unicast().onBackpressureBuffer();
        final List<String> relayedText = new CopyOnWriteArrayList<>();
        final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        final WebSocketSession client = mock(WebSocketSession.class);
        when(client.getHandshakeInfo()).thenReturn(new HandshakeInfo(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/session-1/stream"),
                new HttpHeaders(), Mono.just(ownerJwt()), null));
        when(client.receive()).thenReturn(clientInbound.asFlux());
        when(client.textMessage(anyString())).thenAnswer(invocation ->
                textFrame(clientFactory, invocation.getArgument(0)));
        when(client.send(any(Publisher.class))).thenAnswer(invocation ->
                Flux.from(invocation.<Publisher<WebSocketMessage>>getArgument(0))
                        .doOnNext(message -> relayedText.add(message.getPayloadAsText()))
                        .then());
        when(client.close(any(CloseStatus.class))).thenAnswer(invocation -> {
            closeStatus.set(invocation.getArgument(0));
            return Mono.empty();
        });

        handleSubscription = handler.handle(client).subscribe();
        clientInbound.emitNext(binaryFrame(clientFactory, 0L), Sinks.EmitFailureHandler.FAIL_FAST);

        final Instant deadline = Instant.now().plus(TEST_TIMEOUT);
        while (Instant.now().isBefore(deadline) && closeStatus.get() == null) {
            sleepQuietly();
        }

        assertThat(closeStatus.get()).isEqualTo(CloseStatus.SERVER_ERROR);
        assertThat(relayedText)
                .as("an unpersisted final must never be shown to the desktop client")
                .containsExactly(READY_EVENT);
        assertThat(meters.counter(
                        "audio_gateway_live_stream_transcript_results_total",
                        "outcome", "persisted").count())
                .isZero();
        assertThat(meters.counter(
                        "audio_gateway_live_stream_transcript_results_total",
                        "outcome", "failed").count())
                .isEqualTo(1.0d);
    }

    @Test
    void transcriptPersistenceThatNeverReturnsClosesAtDeliveryDeadline() throws Exception {
        upstreamServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.ws("/ws/stream", (in, out) ->
                        out.sendString(Flux.concat(
                                Flux.just(READY_EVENT),
                                in.receiveFrames()
                                        .ofType(BinaryWebSocketFrame.class)
                                        .map(frame -> "{\"type\":\"final\",\"seq\":0,"
                                                + "\"text\":\"must-not-leak\","
                                                + "\"reason\":\"window\",\"elapsed_ms\":12,"
                                                + "\"source_start_sample\":0,"
                                                + "\"source_end_sample\":2}")))))
                .bindNow();

        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl(
                "ws://127.0.0.1:" + upstreamServer.port() + "/ws/stream");
        properties.getDirectStt().getTranscriptResultStream().setDeliveryAttemptTimeoutMs(50L);
        properties.getDirectStt().getTranscriptResultStream().setDeliveryTotalTimeoutMs(150L);
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
        final LiveSttWebSocketProxyHandler handler = new LiveSttWebSocketProxyHandler(
                sessions, properties, auditSink, hangingSink,
                upstreamClient, new ObjectMapper(), meters);
        when(sessions.get("session-1")).thenReturn(Optional.of(streamingSession(1L, 4L)));

        final NettyDataBufferFactory clientFactory =
                new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT);
        final Sinks.Many<WebSocketMessage> clientInbound =
                Sinks.many().unicast().onBackpressureBuffer();
        final List<String> relayedText = new CopyOnWriteArrayList<>();
        final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        final WebSocketSession client = mock(WebSocketSession.class);
        when(client.getHandshakeInfo()).thenReturn(new HandshakeInfo(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/session-1/stream"),
                new HttpHeaders(), Mono.just(ownerJwt()), null));
        when(client.receive()).thenReturn(clientInbound.asFlux());
        when(client.textMessage(anyString())).thenAnswer(invocation ->
                textFrame(clientFactory, invocation.getArgument(0)));
        when(client.send(any(Publisher.class))).thenAnswer(invocation ->
                Flux.from(invocation.<Publisher<WebSocketMessage>>getArgument(0))
                        .doOnNext(message -> relayedText.add(message.getPayloadAsText()))
                        .then());
        when(client.close(any(CloseStatus.class))).thenAnswer(invocation -> {
            closeStatus.set(invocation.getArgument(0));
            return Mono.empty();
        });

        try {
            handleSubscription = handler.handle(client).subscribe();
            clientInbound.emitNext(binaryFrame(clientFactory, 0L), Sinks.EmitFailureHandler.FAIL_FAST);
            assertThat(enteredSink.await(2, TimeUnit.SECONDS)).isTrue();

            final Instant deadline = Instant.now().plusSeconds(2);
            while (Instant.now().isBefore(deadline) && closeStatus.get() == null) {
                sleepQuietly();
            }

            assertThat(closeStatus.get()).isEqualTo(CloseStatus.SERVER_ERROR);
            assertThat(relayedText).containsExactly(READY_EVENT);
            assertThat(meters.counter(
                            "audio_gateway_live_stream_transcript_results_total",
                            "outcome", "persisted").count())
                    .isZero();
            assertThat(meters.counter(
                            "audio_gateway_live_stream_transcript_results_total",
                            "outcome", "failed").count())
                    .isEqualTo(1.0d);
        } finally {
            releaseSink.countDown();
        }
    }

    @Test
    void eofWithoutDrainedClosesWithServerErrorAfterBoundedTimeout() {
        upstreamServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.ws("/ws/stream", (in, out) ->
                        out.sendString(Flux.concat(
                                Flux.just(readyEvent(1L)),
                                in.receiveFrames()
                                        .ofType(TextWebSocketFrame.class)
                                        .filter(frame -> LiveStreamControlFrame.CANONICAL_EOF.equals(frame.text()))
                                        .map(frame -> "{\"type\":\"eof_ack\"}")
                                        .concatWith(Flux.never())))))
                .bindNow();

        final AudioGatewayProperties properties = new AudioGatewayProperties();
        properties.getDirectStt().getStreaming().setEnabled(true);
        properties.getDirectStt().getStreaming().setStreamUrl(
                "ws://127.0.0.1:" + upstreamServer.port() + "/ws/stream");
        properties.getDirectStt().getTranscriptResultStream().setDeliveryAttemptTimeoutMs(25L);
        properties.getDirectStt().getTranscriptResultStream().setDeliveryTotalTimeoutMs(50L);
        properties.getDirectStt().getStreaming().setTerminalDrainTimeoutMs(1_200L);
        final LiveSttWebSocketProxyHandler handler = new LiveSttWebSocketProxyHandler(
                sessions, properties, auditSink, DirectSttTranscriptResultSink.noop(),
                upstreamClient, new ObjectMapper(), meters);
        when(sessions.get("session-1")).thenReturn(Optional.of(streamingSession(1L, 4L)));

        final NettyDataBufferFactory clientFactory =
                new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT);
        final Sinks.Many<WebSocketMessage> clientInbound =
                Sinks.many().unicast().onBackpressureBuffer();
        final AtomicReference<CloseStatus> closeStatus = new AtomicReference<>();
        final WebSocketSession client = mock(WebSocketSession.class);
        when(client.getHandshakeInfo()).thenReturn(new HandshakeInfo(
                URI.create("ws://gateway/api/v1/audio-gateway/sessions/session-1/stream"),
                new HttpHeaders(), Mono.just(ownerJwt()), null));
        when(client.receive()).thenReturn(clientInbound.asFlux());
        when(client.textMessage(anyString())).thenAnswer(invocation ->
                textFrame(clientFactory, invocation.getArgument(0)));
        when(client.send(any(Publisher.class))).thenAnswer(invocation ->
                Flux.from(invocation.<Publisher<WebSocketMessage>>getArgument(0)).then());
        when(client.close(any(CloseStatus.class))).thenAnswer(invocation -> {
            closeStatus.set(invocation.getArgument(0));
            return Mono.empty();
        });

        handleSubscription = handler.handle(client).subscribe();
        clientInbound.emitNext(
                textFrame(clientFactory, LiveStreamControlFrame.CANONICAL_EOF),
                Sinks.EmitFailureHandler.FAIL_FAST);

        final Instant deadline = Instant.now().plus(TEST_TIMEOUT);
        while (Instant.now().isBefore(deadline) && closeStatus.get() == null) {
            sleepQuietly();
        }

        assertThat(closeStatus.get()).isEqualTo(CloseStatus.SERVER_ERROR);
        assertThat(meters.counter("audio_gateway_live_stream_upstream_failures_total").count())
                .isEqualTo(1.0d);
    }

    private String warnMessages() {
        return warnAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining(" | "));
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static WebSocketMessage binaryFrame(
            final NettyDataBufferFactory factory, final long sequence) {
        final byte[] pcm16 = new byte[]{0x11, 0x22, 0x33, 0x44};
        final byte[] encoded = ByteBuffer
                .allocate(LiveAudioStreamFrame.HEADER_BYTES + pcm16.length)
                .order(ByteOrder.BIG_ENDIAN)
                .put((byte) LiveAudioStreamFrame.VERSION)
                .putLong(sequence)
                .putLong(1_000L + sequence)
                .putShort((short) pcm16.length)
                .put(pcm16)
                .array();
        return new WebSocketMessage(WebSocketMessage.Type.BINARY, factory.wrap(encoded));
    }

    private static WebSocketMessage textFrame(
            final NettyDataBufferFactory factory, final String value) {
        return new WebSocketMessage(
                WebSocketMessage.Type.TEXT,
                factory.wrap(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static JwtAuthenticationToken ownerJwt() {
        return new JwtAuthenticationToken(Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("subject-1")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("companyId", 1L)
                .claim("userId", 4L)
                .build());
    }

    private static SessionRecord streamingSession(final Long tenantId, final Long userId) {
        return new SessionRecord(
                "session-1", tenantId, userId, "meeting-1", "device-1", "tr",
                AudioFormat.PCM16, 16_000, 1, "start-key-123456", 1_000L,
                SessionState.STREAMING,
                -1L, 0L, 0L, null, null, null, 0L, 1_000L);
    }
}
