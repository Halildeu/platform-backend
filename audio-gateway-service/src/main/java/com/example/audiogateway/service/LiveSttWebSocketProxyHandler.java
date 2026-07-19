package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.TranscriptResult;
import com.example.audiogateway.service.AudioGatewayAuditSink.AuditEvent;
import com.example.audiogateway.service.AudioSessionRegistry.LiveFrameCommand;
import com.example.audiogateway.service.AudioSessionRegistry.LiveFrameOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Authenticated, bounded, in-flight-only gateway bridge to live-stt /ws/stream.
 */
public class LiveSttWebSocketProxyHandler implements WebSocketHandler, DisposableBean {

    static final String PATH_PREFIX = "/api/v1/audio-gateway/sessions/";
    static final String PATH_SUFFIX = "/stream";
    static final String UPSTREAM_PROTOCOL = "source-ranges-v1";
    private static final long TERMINAL_TRANSPORT_MARGIN_MS = 1_000L;

    private static final Logger log = LoggerFactory.getLogger(LiveSttWebSocketProxyHandler.class);

    private final AudioSessionRegistry sessions;
    private final AudioGatewayProperties properties;
    private final AudioGatewayAuditSink auditSink;
    private final DirectSttTranscriptResultSink transcriptResultSink;
    private final WebSocketClient upstreamClient;
    private final URI upstreamUri;
    private final ObjectMapper objectMapper;
    private final Scheduler transcriptSinkScheduler;
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();
    private final Counter acceptedFrames;
    private final Counter duplicateFrames;
    private final Counter rejectedFrames;
    private final Counter upstreamFailures;
    private final Counter transcriptResultSuccess;
    private final Counter transcriptResultFailures;

    public LiveSttWebSocketProxyHandler(
            final AudioSessionRegistry sessions,
            final AudioGatewayProperties properties,
            final AudioGatewayAuditSink auditSink,
            final DirectSttTranscriptResultSink transcriptResultSink,
            final WebSocketClient upstreamClient,
            final ObjectMapper objectMapper,
            final MeterRegistry meters) {
        this.sessions = sessions;
        this.properties = properties;
        this.auditSink = auditSink;
        this.transcriptResultSink = transcriptResultSink;
        this.upstreamClient = upstreamClient;
        this.upstreamUri = UriComponentsBuilder
                .fromUriString(properties.getDirectStt().getStreaming().getStreamUrl())
                .queryParam("protocol", UPSTREAM_PROTOCOL)
                .build(true)
                .toUri();
        this.objectMapper = objectMapper;
        final int sinkConcurrency = Math.max(1, properties.getDirectStt().getMaxInFlight());
        this.transcriptSinkScheduler = Schedulers.newBoundedElastic(
                sinkConcurrency,
                Math.max(6, sinkConcurrency * 6),
                "live-stt-transcript-sink");
        this.acceptedFrames = meters.counter("audio_gateway_live_stream_frames_total", "outcome", "accepted");
        this.duplicateFrames = meters.counter("audio_gateway_live_stream_frames_total", "outcome", "duplicate");
        this.rejectedFrames = meters.counter("audio_gateway_live_stream_frames_total", "outcome", "rejected");
        this.upstreamFailures = meters.counter("audio_gateway_live_stream_upstream_failures_total");
        this.transcriptResultSuccess = meters.counter(
                "audio_gateway_live_stream_transcript_results_total", "outcome", "persisted");
        this.transcriptResultFailures = meters.counter(
                "audio_gateway_live_stream_transcript_results_total", "outcome", "failed");
        Gauge.builder("audio_gateway_live_stream_connections", activeSessions, Set::size)
                .register(meters);
    }

    @Override
    public void destroy() {
        transcriptSinkScheduler.dispose();
    }

    @Override
    public Mono<Void> handle(final WebSocketSession clientSession) {
        return clientSession.getHandshakeInfo().getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(optionalAuthentication -> {
                    if (optionalAuthentication.isEmpty()) {
                        return clientSession.close(CloseStatus.POLICY_VIOLATION);
                    }
                    final JwtAuthenticationToken authentication =
                            optionalAuthentication.orElseThrow();
                    final String sessionId = sessionId(clientSession);
                    final Long tenantId = claimAsLong(
                            authentication, properties.getJwt().getTenantClaim());
                    final Long userId = claimAsLong(
                            authentication, properties.getJwt().getUserClaim());
                    if (tenantId == null || userId == null) {
                        return clientSession.close(CloseStatus.POLICY_VIOLATION);
                    }
                    final SessionRecord record = sessions.get(sessionId).orElse(null);
                    if (record == null
                            || !Objects.equals(record.tenantId(), tenantId)
                            || !Objects.equals(record.userId(), userId)
                            || record.state() == SessionState.FINISHED) {
                        return clientSession.close(CloseStatus.POLICY_VIOLATION);
                    }
                    if (record.audioFormat() != AudioFormat.PCM16
                            || record.sampleRateHz() != 16_000
                            || record.channels() != 1) {
                        return clientSession.close(CloseStatus.NOT_ACCEPTABLE);
                    }
                    if (!activeSessions.add(sessionId)) {
                        return clientSession.close(CloseStatus.POLICY_VIOLATION);
                    }
                    final String correlationId = correlationId(clientSession);
                    safeAudit(new AuditEvent.TranscriptEventsAccessed(
                            record.sessionId(),
                            record.tenantId(),
                            record.userId(),
                            record.meetingId(),
                            "WEBSOCKET",
                            "",
                            0,
                            correlationId,
                            System.currentTimeMillis()));

                    return upstreamClient.execute(upstreamUri, upstream ->
                                    bridge(clientSession, upstream, record, correlationId))
                            .doOnError(error -> {
                                if (error instanceof ClientFrameException) {
                                    return;
                                }
                                upstreamFailures.increment();
                                log.warn(
                                        "Live STT WebSocket bridge failed err={} sessionId={} correlationId={}",
                                        error.getClass().getSimpleName(),
                                        sessionId,
                                        correlationId);
                            })
                            .onErrorResume(
                                    ClientFrameException.class,
                                    error -> clientSession.close(CloseStatus.BAD_DATA))
                            .onErrorResume(error -> clientSession.close(CloseStatus.SERVER_ERROR))
                            .doFinally(signal -> {
                                activeSessions.remove(sessionId);
                            });
                });
    }

    private Mono<Void> bridge(
            final WebSocketSession client,
            final WebSocketSession upstream,
            final SessionRecord record,
            final String correlationId) {
        final int maxFrameBytes = properties.getDirectStt().getStreaming().getMaxFrameBytes();
        final int maxControlBytes = properties.getDirectStt().getStreaming()
                .getMaxTerminalControlBytes();
        final Duration readyTimeout = Duration.ofMillis(properties.getDirectStt().getStreaming()
                .getReadyTimeoutMs());
        final Duration drainTimeout = Duration.ofMillis(properties.getDirectStt().getStreaming()
                .getTerminalDrainTimeoutMs());
        final AtomicBoolean readyObserved = new AtomicBoolean();
        final AtomicBoolean eofSent = new AtomicBoolean();
        final AtomicBoolean eofAckObserved = new AtomicBoolean();
        final AtomicBoolean drainedObserved = new AtomicBoolean();
        final Sinks.One<Void> upstreamReady = Sinks.one();
        final Sinks.One<Void> drainedRelayed = Sinks.one();
        final LiveTranscriptWindowAccumulator transcriptWindow =
                new LiveTranscriptWindowAccumulator(properties.getDirectStt().getStreaming()
                        .getSourceHistoryMaxBytes());

        final Flux<WebSocketMessage> framesToUpstream = client.receive()
                .limitRate(1)
                .<WebSocketMessage>handle((message, sink) -> {
                    // Do NOT release the payload: reactor-netty owns the inbound frame and
                    // releases it after this handler returns (FluxReceive.drainReceiver ->
                    // DefaultByteBufHolder.release). Releasing the DataBuffer here drops the
                    // shared ByteBuf's refCnt to 0, and the framework's own release then
                    // throws IllegalReferenceCountException. Copy synchronously instead —
                    // the bytes below outlive the frame, the buffer does not.
                    if (message.getType() == WebSocketMessage.Type.TEXT) {
                        final int readable = message.getPayload().readableByteCount();
                        if (readable > maxControlBytes || !eofSent.compareAndSet(false, true)) {
                            rejectedFrames.increment();
                            sink.error(new ClientFrameException(
                                    "live stream terminal control is invalid"));
                            return;
                        }
                        final LiveStreamControlFrame control;
                        try {
                            control = LiveStreamControlFrame.decode(
                                    message.getPayloadAsText(), maxControlBytes, objectMapper);
                        } catch (IllegalArgumentException error) {
                            eofSent.set(false);
                            rejectedFrames.increment();
                            sink.error(new ClientFrameException(error.getMessage()));
                            return;
                        }
                        sink.next(upstream.textMessage(control.upstreamPayload()));
                        return;
                    }
                    if (message.getType() != WebSocketMessage.Type.BINARY || eofSent.get()) {
                        rejectedFrames.increment();
                        sink.error(new ClientFrameException(
                                "live stream frame type or terminal order is invalid"));
                        return;
                    }
                    final int readable = message.getPayload().readableByteCount();
                    if (readable > maxFrameBytes + LiveAudioStreamFrame.HEADER_BYTES) {
                        rejectedFrames.increment();
                        sink.error(new ClientFrameException(
                                "live audio frame exceeds configured bound"));
                        return;
                    }
                    final byte[] encoded = new byte[readable];
                    message.getPayload().read(encoded);
                    final LiveAudioStreamFrame frame;
                    try {
                        frame = LiveAudioStreamFrame.decode(encoded, maxFrameBytes);
                    } catch (IllegalArgumentException error) {
                        rejectedFrames.increment();
                        sink.error(new ClientFrameException(error.getMessage()));
                        return;
                    }
                    final LiveFrameOutcome outcome = sessions.admitLiveFrame(
                            new LiveFrameCommand(
                                    record.sessionId(), record.tenantId(), record.userId(),
                                    frame.chunkSeq(), System.currentTimeMillis()),
                            current -> emitComputePlaneAudit(current, frame, correlationId));
                    if (outcome instanceof LiveFrameOutcome.Duplicate) {
                        duplicateFrames.increment();
                        return;
                    }
                    if (!(outcome instanceof LiveFrameOutcome.Accepted)) {
                        rejectedFrames.increment();
                        sink.error(new ClientFrameException(
                                "live audio sequence, ownership, or session state is invalid"));
                        return;
                    }
                    acceptedFrames.increment();
                    transcriptWindow.append(frame, record.sampleRateHz(), record.channels());
                    sink.next(upstream.binaryMessage(factory ->
                            factory.wrap(frame.toFloat32LittleEndian())));
                })
                // EOF is terminal for the upload leg even when the client keeps its
                // inbound socket open while waiting for final/drained events.
                .takeUntil(message -> message.getType() == WebSocketMessage.Type.TEXT)
                // The AI endpoint can spend minutes loading pinned models. Do not admit,
                // account or forward any desktop audio until it proves the exact source
                // range protocol is ready; otherwise early microphone frames are lost.
                .delaySubscription(upstreamReady.asMono().timeout(readyTimeout));

        final Flux<RelayedEvent> relayedEvents = upstream.receive()
                .limitRate(1)
                // Detach from reactor-netty's frame before the asynchronous persistence
                // boundary. Keeping even a reference to the framework-owned message inside
                // concatMap lets cancellation/discard race its own release lifecycle.
                .map(this::copyUpstreamMessage)
                .concatMap(message -> relayUpstreamMessage(
                        client,
                        message,
                        record,
                        correlationId,
                        readyObserved,
                        upstreamReady,
                        eofSent,
                        eofAckObserved,
                        drainedObserved,
                        transcriptWindow), 1)
                .takeUntil(RelayedEvent::drained);

        final Mono<Void> upload = upstream.send(framesToUpstream)
                .then(Mono.defer(() -> eofSent.get()
                        // Once drained has been relayed, keep this leg pending so the download
                        // leg wins only after client.send(...) has completed. Without EOF, normal
                        // client disconnect keeps the original first-completion cleanup behavior.
                        ? drainedRelayed.asMono().timeout(drainTimeout).then(Mono.never())
                        : Mono.empty()));
        final Mono<Void> download = client.send(relayedEvents.map(RelayedEvent::message))
                .doOnSuccess(ignored -> {
                    if (drainedObserved.get()) {
                        drainedRelayed.tryEmitEmpty();
                    }
                })
                .then(Mono.defer(() -> eofSent.get() && !drainedObserved.get()
                        ? Mono.error(new TerminalDrainException())
                        : Mono.empty()));
        // Terminal EOF is a two-leg contract. The upload leg above cannot win after drained,
        // so the download completes only after the terminal event reached the desktop. For a
        // non-EOF disconnect, either leg may still terminate the bridge promptly.
        return Mono.firstWithSignal(upload, download).then();
    }

    private Mono<RelayedEvent> relayUpstreamMessage(
            final WebSocketSession client,
            final CopiedUpstreamMessage message,
            final SessionRecord record,
            final String correlationId,
            final AtomicBoolean readyObserved,
            final Sinks.One<Void> upstreamReady,
            final AtomicBoolean eofSent,
            final AtomicBoolean eofAckObserved,
            final AtomicBoolean drainedObserved,
            final LiveTranscriptWindowAccumulator transcriptWindow) {
        if (message instanceof CopiedUpstreamMessage.Text textMessage) {
            final String event = textMessage.value();
            final UpstreamEvent parsed = parseUpstreamEvent(event);
            if (parsed instanceof UpstreamEvent.Ready ready) {
                if (!readyObserved.compareAndSet(false, true)) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT emitted duplicate ready event"));
                }
                final long persistenceTimeoutMs = properties.getDirectStt()
                        .getTranscriptResultStream().getDeliveryTotalTimeoutMs();
                final long requiredDrainMs = Math.addExact(
                        Math.addExact(ready.terminalTimeoutMs(), persistenceTimeoutMs),
                        TERMINAL_TRANSPORT_MARGIN_MS);
                final long configuredDrainMs = properties.getDirectStt().getStreaming()
                        .getTerminalDrainTimeoutMs();
                if (configuredDrainMs < requiredDrainMs) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT terminal timeout exceeds gateway drain budget"));
                }
                upstreamReady.tryEmitEmpty();
            }
            if (parsed instanceof UpstreamEvent.EofAck) {
                if (!eofSent.get() || !eofAckObserved.compareAndSet(false, true)) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT eof_ack violates terminal event order"));
                }
            }
            if (parsed instanceof UpstreamEvent.Partial && eofAckObserved.get()) {
                return Mono.error(new IllegalArgumentException(
                        "live STT partial arrived after eof_ack"));
            }
            if (parsed instanceof UpstreamEvent.Final finalEvent) {
                final LiveTranscriptWindowAccumulator.Window window =
                        transcriptWindow.take(
                                finalEvent.sequence(),
                                finalEvent.sourceStartSample(),
                                finalEvent.sourceEndSample());
                final TranscriptResult result = new TranscriptResult(
                        finalEvent.text(),
                        record.language(),
                        null,
                        window.durationMs() / 1_000.0d,
                        finalEvent.elapsedMs(),
                        null,
                        null,
                        null,
                        null);
                final DirectSttTranscriptResultContext context =
                        liveTranscriptContext(record, correlationId, finalEvent.reason(), window);
                return persistLiveTranscriptResult(result, context)
                        .doOnSuccess(ignored -> transcriptResultSuccess.increment())
                        .doOnError(error -> {
                            transcriptResultFailures.increment();
                            log.warn(
                                    "Live STT final persistence failed err={} sessionId={} "
                                            + "windowSeq={} correlationId={}",
                                    error.getClass().getSimpleName(),
                                    record.sessionId(),
                                    window.windowSeq(),
                                    correlationId);
                        })
                        .thenReturn(new RelayedEvent(client.textMessage(event), false));
            }
            final boolean drained = parsed instanceof UpstreamEvent.Drained;
            if (drained) {
                if (!eofSent.get() || !eofAckObserved.get()
                        || !drainedObserved.compareAndSet(false, true)) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT drained violates terminal event order"));
                }
            }
            return Mono.just(new RelayedEvent(client.textMessage(event), drained));
        }
        if (message instanceof CopiedUpstreamMessage.Ping ping) {
            return Mono.just(new RelayedEvent(
                    client.pingMessage(factory -> factory.wrap(ping.payload())), false));
        }
        return Mono.empty();
    }

    private Mono<Void> persistLiveTranscriptResult(
            final TranscriptResult result,
            final DirectSttTranscriptResultContext context) {
        final var delivery = properties.getDirectStt().getTranscriptResultStream();
        final Duration attemptTimeout = Duration.ofMillis(delivery.getDeliveryAttemptTimeoutMs());
        final Duration totalTimeout = Duration.ofMillis(delivery.getDeliveryTotalTimeoutMs());
        return Mono.defer(() -> Mono
                        .fromRunnable(() -> transcriptResultSink.emit(result, context))
                        .subscribeOn(transcriptSinkScheduler)
                        .timeout(attemptTimeout))
                .retryWhen(Retry.backoff(5, Duration.ofMillis(100))
                        .maxBackoff(Duration.ofSeconds(2))
                        .jitter(0.2d))
                // Bounds retry backoff and releases the bridge's connection-local PCM history.
                // The Redis client command timeout remains the lower-level blocking-call guard.
                .timeout(totalTimeout)
                .then();
    }

    private CopiedUpstreamMessage copyUpstreamMessage(final WebSocketMessage message) {
        if (message.getType() == WebSocketMessage.Type.TEXT) {
            final int readable = message.getPayload().readableByteCount();
            if (readable > properties.getDirectStt().getMaxResponseBytes()) {
                throw new IllegalArgumentException(
                        "live STT event exceeds configured response bound");
            }
            return new CopiedUpstreamMessage.Text(message.getPayloadAsText());
        }
        if (message.getType() == WebSocketMessage.Type.PING) {
            final byte[] payload = new byte[message.getPayload().readableByteCount()];
            message.getPayload().read(payload);
            return new CopiedUpstreamMessage.Ping(payload);
        }
        return new CopiedUpstreamMessage.Ignored();
    }

    private DirectSttTranscriptResultContext liveTranscriptContext(
            final SessionRecord record,
            final String correlationId,
            final String reason,
            final LiveTranscriptWindowAccumulator.Window window) {
        return new DirectSttTranscriptResultContext(
                record.sessionId(),
                record.tenantId(),
                record.userId(),
                window.lastChunkSeq(),
                window.startedAtMs(),
                window.windowSeq(),
                window.firstChunkSeq(),
                window.lastChunkSeq(),
                window.startedAtMs(),
                window.endedAtMs(),
                window.durationMs(),
                "stream_" + reason,
                record.meetingId(),
                record.deviceId(),
                record.language(),
                record.audioFormat().name(),
                record.sampleRateHz(),
                record.channels(),
                correlationId,
                window.sha256(),
                window.byteLength());
    }

    private UpstreamEvent parseUpstreamEvent(final String value) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("live STT event is not valid JSON", error);
        }
        if (root == null || !root.isObject() || !root.path("type").isTextual()) {
            throw new IllegalArgumentException("live STT event type is missing");
        }
        final String type = root.path("type").textValue();
        if ("ready".equals(type)) {
            final JsonNode capabilities = root.path("capabilities");
            final boolean hasProtocolCapability = capabilities.isArray()
                    && java.util.stream.StreamSupport.stream(capabilities.spliterator(), false)
                            .anyMatch(item -> item.isTextual()
                                    && UPSTREAM_PROTOCOL.equals(item.textValue()));
            final JsonNode terminalTimeout = root.path("terminal_timeout_ms");
            if (!UPSTREAM_PROTOCOL.equals(root.path("protocol").asText())
                    || !"stable-v1".equals(root.path("partial_mode").asText())
                    || !root.path("supports_eof").asBoolean(false)
                    || root.path("sample_rate").asInt(-1) != 16_000
                    || !hasProtocolCapability
                    || !terminalTimeout.isIntegralNumber()
                    || !terminalTimeout.canConvertToLong()
                    || terminalTimeout.longValue() <= 0L
                    || terminalTimeout.longValue() > 120_000L) {
                throw new IllegalArgumentException(
                        "live STT ready event does not satisfy source range protocol");
            }
            return new UpstreamEvent.Ready(terminalTimeout.longValue());
        }
        if ("drained".equals(type)) {
            return new UpstreamEvent.Drained();
        }
        if ("eof_ack".equals(type)) {
            return new UpstreamEvent.EofAck();
        }
        if ("partial".equals(type)) {
            return new UpstreamEvent.Partial();
        }
        if (!"final".equals(type)) {
            return new UpstreamEvent.Other();
        }
        final JsonNode sequence = root.path("seq");
        final JsonNode text = root.path("text");
        if (!sequence.isIntegralNumber() || !sequence.canConvertToLong()
                || sequence.longValue() < 0L
                || !text.isTextual() || text.textValue().isBlank()) {
            throw new IllegalArgumentException("live STT final event is incomplete");
        }
        final JsonNode elapsed = root.path("elapsed_ms");
        final Double elapsedMs;
        if (elapsed.isMissingNode() || elapsed.isNull()) {
            elapsedMs = null;
        } else if (!elapsed.isNumber()
                || !Double.isFinite(elapsed.doubleValue())
                || elapsed.doubleValue() < 0.0d) {
            throw new IllegalArgumentException("live STT final elapsed_ms is invalid");
        } else {
            elapsedMs = elapsed.doubleValue();
        }
        final String reason = root.path("reason").isTextual()
                && root.path("reason").textValue().matches("[A-Za-z0-9_.:-]{1,64}")
                ? root.path("reason").textValue()
                : "final";
        final JsonNode sourceStart = root.path("source_start_sample");
        final JsonNode sourceEnd = root.path("source_end_sample");
        if (!sourceStart.isIntegralNumber() || !sourceStart.canConvertToLong()
                || !sourceEnd.isIntegralNumber() || !sourceEnd.canConvertToLong()
                || sourceStart.longValue() < 0L
                || sourceEnd.longValue() <= sourceStart.longValue()) {
            throw new IllegalArgumentException("live STT final source sample range is invalid");
        }
        return new UpstreamEvent.Final(
                sequence.longValue(), text.textValue(), reason, elapsedMs,
                sourceStart.longValue(), sourceEnd.longValue());
    }

    private void emitComputePlaneAudit(
            final SessionRecord record,
            final LiveAudioStreamFrame frame,
            final String correlationId) {
        final int durationMs = frame.durationMs(record.sampleRateHz(), record.channels());
        final long endedAtMs = frame.capturedAtMs() + durationMs;
        auditSink.emit(new AuditEvent.ChunkForwardedToComputePlane(
                record.sessionId(),
                record.tenantId(),
                record.userId(),
                record.meetingId(),
                record.deviceId(),
                record.language(),
                frame.chunkSeq(),
                frame.chunkSeq(),
                frame.chunkSeq(),
                frame.chunkSeq(),
                frame.capturedAtMs(),
                endedAtMs,
                durationMs,
                "stream",
                record.audioFormat().name(),
                record.sampleRateHz(),
                record.channels(),
                sha256(frame.pcm16()),
                frame.pcm16().length,
                correlationId,
                System.currentTimeMillis(),
                "live-stt-websocket"));
    }

    private void safeAudit(final AuditEvent event) {
        try {
            auditSink.emit(event);
        } catch (RuntimeException error) {
            log.warn("Live stream access audit failed err={}", error.getClass().getSimpleName());
        }
    }

    static String sessionId(final WebSocketSession session) {
        final String path = session.getHandshakeInfo().getUri().getPath();
        if (!path.startsWith(PATH_PREFIX) || !path.endsWith(PATH_SUFFIX)) {
            return "";
        }
        return path.substring(PATH_PREFIX.length(), path.length() - PATH_SUFFIX.length());
    }

    private static String correlationId(final WebSocketSession session) {
        final String supplied = session.getHandshakeInfo().getHeaders().getFirst("X-Correlation-Id");
        return supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied;
    }

    private static Long claimAsLong(
            final JwtAuthenticationToken authentication,
            final String claimName) {
        final Object value = authentication.getToken().getClaim(claimName);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String sha256(final byte[] payload) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class ClientFrameException extends RuntimeException {
        private ClientFrameException(final String message) {
            super(message);
        }
    }

    private static final class TerminalDrainException extends RuntimeException {
        private TerminalDrainException() {
            super("live STT terminal drain did not complete");
        }
    }

    private record RelayedEvent(WebSocketMessage message, boolean drained) {
    }

    private sealed interface CopiedUpstreamMessage {
        record Text(String value) implements CopiedUpstreamMessage {
        }

        record Ping(byte[] payload) implements CopiedUpstreamMessage {
        }

        record Ignored() implements CopiedUpstreamMessage {
        }
    }

    private sealed interface UpstreamEvent {
        record Ready(long terminalTimeoutMs) implements UpstreamEvent {
        }

        record Final(
                long sequence,
                String text,
                String reason,
                Double elapsedMs,
                long sourceStartSample,
                long sourceEndSample)
                implements UpstreamEvent {
        }

        record Drained() implements UpstreamEvent {
        }

        record EofAck() implements UpstreamEvent {
        }

        record Partial() implements UpstreamEvent {
        }

        record Other() implements UpstreamEvent {
        }
    }
}
