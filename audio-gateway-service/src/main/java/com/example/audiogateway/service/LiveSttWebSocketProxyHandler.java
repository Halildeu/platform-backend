package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.service.AudioGatewayAuditSink.AuditEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Authenticated, bounded, in-flight-only gateway bridge to live-stt /ws/stream.
 */
public class LiveSttWebSocketProxyHandler implements WebSocketHandler {

    static final String PATH_PREFIX = "/api/v1/audio-gateway/sessions/";
    static final String PATH_SUFFIX = "/stream";

    private static final Logger log = LoggerFactory.getLogger(LiveSttWebSocketProxyHandler.class);

    private final AudioSessionRegistry sessions;
    private final AudioGatewayProperties properties;
    private final AudioGatewayAuditSink auditSink;
    private final WebSocketClient upstreamClient;
    private final URI upstreamUri;
    private final LiveStreamSequenceTracker sequences;
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();
    private final Counter acceptedFrames;
    private final Counter duplicateFrames;
    private final Counter rejectedFrames;
    private final Counter upstreamFailures;

    public LiveSttWebSocketProxyHandler(
            final AudioSessionRegistry sessions,
            final AudioGatewayProperties properties,
            final AudioGatewayAuditSink auditSink,
            final WebSocketClient upstreamClient,
            final MeterRegistry meters) {
        this.sessions = sessions;
        this.properties = properties;
        this.auditSink = auditSink;
        this.upstreamClient = upstreamClient;
        this.upstreamUri = URI.create(properties.getDirectStt().getStreaming().getStreamUrl());
        this.sequences = new LiveStreamSequenceTracker(
                properties.getDirectStt().getStreaming().getMaxTrackedSessions());
        this.acceptedFrames = meters.counter("audio_gateway_live_stream_frames_total", "outcome", "accepted");
        this.duplicateFrames = meters.counter("audio_gateway_live_stream_frames_total", "outcome", "duplicate");
        this.rejectedFrames = meters.counter("audio_gateway_live_stream_frames_total", "outcome", "rejected");
        this.upstreamFailures = meters.counter("audio_gateway_live_stream_upstream_failures_total");
        Gauge.builder("audio_gateway_live_stream_connections", activeSessions, Set::size)
                .register(meters);
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
                                sequences.release(sessionId);
                            });
                });
    }

    private Mono<Void> bridge(
            final WebSocketSession client,
            final WebSocketSession upstream,
            final SessionRecord record,
            final String correlationId) {
        final int maxFrameBytes = properties.getDirectStt().getStreaming().getMaxFrameBytes();

        final Flux<WebSocketMessage> audioToUpstream = client.receive()
                .limitRate(1)
                .handle((message, sink) -> {
                    if (message.getType() != WebSocketMessage.Type.BINARY) {
                        rejectedFrames.increment();
                        DataBufferUtils.release(message.getPayload());
                        sink.error(new ClientFrameException(
                                "live audio stream accepts binary frames only"));
                        return;
                    }
                    final int readable = message.getPayload().readableByteCount();
                    if (readable > maxFrameBytes + LiveAudioStreamFrame.HEADER_BYTES) {
                        rejectedFrames.increment();
                        DataBufferUtils.release(message.getPayload());
                        sink.error(new ClientFrameException(
                                "live audio frame exceeds configured bound"));
                        return;
                    }
                    final byte[] encoded = new byte[readable];
                    message.getPayload().read(encoded);
                    DataBufferUtils.release(message.getPayload());
                    final LiveAudioStreamFrame frame;
                    try {
                        frame = LiveAudioStreamFrame.decode(encoded, maxFrameBytes);
                    } catch (IllegalArgumentException error) {
                        rejectedFrames.increment();
                        sink.error(new ClientFrameException(error.getMessage()));
                        return;
                    }
                    final LiveStreamSequenceTracker.Outcome outcome = sequences.accept(
                            record.sessionId(), record.lastAcceptedChunkSeq(), frame.chunkSeq());
                    if (outcome == LiveStreamSequenceTracker.Outcome.DUPLICATE) {
                        duplicateFrames.increment();
                        return;
                    }
                    if (outcome != LiveStreamSequenceTracker.Outcome.ACCEPTED) {
                        rejectedFrames.increment();
                        sink.error(new ClientFrameException(
                                "live audio sequence is non-contiguous or tracker capacity is exhausted"));
                        return;
                    }

                    try {
                        emitComputePlaneAudit(record, frame, correlationId);
                    } catch (RuntimeException error) {
                        sequences.rollbackAccepted(record.sessionId(), frame.chunkSeq());
                        throw error;
                    }
                    acceptedFrames.increment();
                    sink.next(upstream.binaryMessage(factory ->
                            factory.wrap(frame.toFloat32LittleEndian())));
                });

        final Flux<WebSocketMessage> eventsToClient = upstream.receive()
                .limitRate(1)
                .handle((message, sink) -> {
                    if (message.getType() == WebSocketMessage.Type.TEXT) {
                        final int readable = message.getPayload().readableByteCount();
                        if (readable > properties.getDirectStt().getMaxResponseBytes()) {
                            DataBufferUtils.release(message.getPayload());
                            sink.error(new IllegalArgumentException(
                                    "live STT event exceeds configured response bound"));
                            return;
                        }
                        final String payload = message.getPayloadAsText();
                        DataBufferUtils.release(message.getPayload());
                        sink.next(client.textMessage(payload));
                    } else if (message.getType() == WebSocketMessage.Type.PING) {
                        final byte[] payload = new byte[message.getPayload().readableByteCount()];
                        message.getPayload().read(payload);
                        DataBufferUtils.release(message.getPayload());
                        sink.next(client.pingMessage(factory -> factory.wrap(payload)));
                    } else {
                        DataBufferUtils.release(message.getPayload());
                    }
                });

        final Mono<Void> upload = upstream.send(audioToUpstream);
        final Mono<Void> download = client.send(eventsToClient);
        return Mono.firstWithSignal(upload, download).then();
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
}
