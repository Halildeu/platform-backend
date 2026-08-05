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
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    static final String SPEECHMATICS_UPLOAD_TERMINAL_MARKER =
            "__gateway_speechmatics_upload_terminal__";
    private static final long TERMINAL_TRANSPORT_MARGIN_MS = 1_000L;
    private static final int CLIENT_CONTROL_EVENT_BUFFER_SIZE = 64;
    private static final int UPSTREAM_UPLOAD_BUFFER_SIZE = 64;

    private static final Logger log = LoggerFactory.getLogger(LiveSttWebSocketProxyHandler.class);

    private final AudioSessionRegistry sessions;
    private final AudioGatewayProperties properties;
    private final AudioGatewayAuditSink auditSink;
    private final DirectSttTranscriptResultSink transcriptResultSink;
    private final WebSocketClient upstreamClient;
    private final WebSocketClient speechmaticsClient;
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
            final WebSocketClient speechmaticsClient,
            final ObjectMapper objectMapper,
            final MeterRegistry meters) {
        this.sessions = sessions;
        this.properties = properties;
        this.auditSink = auditSink;
        this.transcriptResultSink = transcriptResultSink;
        this.upstreamClient = upstreamClient;
        this.speechmaticsClient = speechmaticsClient;
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

    LiveSttWebSocketProxyHandler(
            final AudioSessionRegistry sessions,
            final AudioGatewayProperties properties,
            final AudioGatewayAuditSink auditSink,
            final DirectSttTranscriptResultSink transcriptResultSink,
            final WebSocketClient upstreamClient,
            final ObjectMapper objectMapper,
            final MeterRegistry meters) {
        this(sessions, properties, auditSink, transcriptResultSink, upstreamClient,
                upstreamClient, objectMapper, meters);
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
                    if (!"internal".equals(record.sttProvider())
                            && !"speechmatics".equals(record.sttProvider())) {
                        return clientSession.close(CloseStatus.NOT_ACCEPTABLE);
                    }
                    if ("speechmatics".equals(record.sttProvider())
                            && !"realtime".equals(record.transcriptionMode())) {
                        return clientSession.close(CloseStatus.NOT_ACCEPTABLE);
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

                    final SpeechmaticsLiveProtocolAdapter speechmatics =
                            "speechmatics".equals(record.sttProvider())
                                    ? new SpeechmaticsLiveProtocolAdapter(
                                            objectMapper,
                                            properties.getDirectStt().getSpeechmatics())
                                    : null;
                    final Mono<Void> connection = speechmatics == null
                            ? upstreamClient.execute(upstreamUri, upstream ->
                                    bridge(clientSession, upstream, record, correlationId, null))
                            : speechmaticsClient.execute(
                                    speechmatics.endpoint(),
                                    speechmatics.authorizationHeaders(),
                                    upstream -> bridge(
                                            clientSession,
                                            upstream,
                                            record,
                                            correlationId,
                                            speechmatics));
                    return connection
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
            final String correlationId,
            final SpeechmaticsLiveProtocolAdapter speechmatics) {
        final int maxFrameBytes = properties.getDirectStt().getStreaming().getMaxFrameBytes();
        final int maxTerminalControlBytes = properties.getDirectStt().getStreaming()
                .getMaxTerminalControlBytes();
        final int maxControlBytes = properties.getDirectStt().getStreaming()
                .getMaxClientControlBytes();
        final Duration readyTimeout = Duration.ofMillis(properties.getDirectStt().getStreaming()
                .getReadyTimeoutMs());
        final Duration drainTimeout = Duration.ofMillis(properties.getDirectStt().getStreaming()
                .getTerminalDrainTimeoutMs());
        final AtomicBoolean readyObserved = new AtomicBoolean();
        final AtomicBoolean contextSent = new AtomicBoolean();
        final AtomicBoolean audioSent = new AtomicBoolean();
        final AtomicBoolean eofSent = new AtomicBoolean();
        final AtomicBoolean eofAckObserved = new AtomicBoolean();
        final AtomicBoolean drainedObserved = new AtomicBoolean();
        final AtomicBoolean errorObserved = new AtomicBoolean();
        final AtomicLong speechmaticsFrameCount = new AtomicLong();
        final AtomicLong acceptedSamples = new AtomicLong();
        final Sinks.One<Void> upstreamReady = Sinks.one();
        final Sinks.One<Void> drainedRelayed = Sinks.one();
        final Sinks.Many<RelayedEvent> clientControlEvents = Sinks.many()
                .unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(
                        CLIENT_CONTROL_EVENT_BUFFER_SIZE));
        final Sinks.Many<WebSocketMessage> upstreamUploadFrames = Sinks.many()
                .unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(
                        UPSTREAM_UPLOAD_BUFFER_SIZE));
        final LiveTranscriptWindowAccumulator transcriptWindow =
                new LiveTranscriptWindowAccumulator(properties.getDirectStt().getStreaming()
                        .getSourceHistoryMaxBytes());

        final Flux<WebSocketMessage> admittedClientFrames = client.receive()
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
                        if (readable > maxControlBytes) {
                            rejectedFrames.increment();
                            sink.error(new ClientFrameException(
                                    "live stream control is invalid"));
                            return;
                        }
                        if (eofSent.get()) {
                            rejectedFrames.increment();
                            return;
                        }
                        final LiveStreamControlFrame control;
                        try {
                            control = LiveStreamControlFrame.decode(
                                    message.getPayloadAsText(), maxControlBytes, objectMapper);
                        } catch (IllegalArgumentException error) {
                            rejectedFrames.increment();
                            sink.error(new ClientFrameException(error.getMessage()));
                            return;
                        }
                        if (control.type() == LiveStreamControlFrame.Type.CONTEXT) {
                            if (audioSent.get() || !contextSent.compareAndSet(false, true)) {
                                rejectedFrames.increment();
                                sink.error(new ClientFrameException(
                                        "live stream context order is invalid"));
                                return;
                            }
                        } else if (readable > maxTerminalControlBytes
                                || !eofSent.compareAndSet(false, true)) {
                            rejectedFrames.increment();
                            sink.error(new ClientFrameException(
                                    "live stream terminal control is invalid"));
                            return;
                        }
                        if (speechmatics == null) {
                            sink.next(upstream.textMessage(control.upstreamPayload(objectMapper)));
                        } else if (control.terminal()) {
                            // Emit an internal marker through the same serialized upload
                            // publisher. A side-channel sink can race Reactor Netty's
                            // currently handled EOF frame and leave client.receive() open.
                            // The transform below consumes this marker and completes without
                            // ever forwarding it to Speechmatics.
                            sink.next(upstream.textMessage(
                                    SPEECHMATICS_UPLOAD_TERMINAL_MARKER));
                        }
                        return;
                    }
                    if (eofSent.get()) {
                        rejectedFrames.increment();
                        return;
                    }
                    if (message.getType() != WebSocketMessage.Type.BINARY) {
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
                        emitAudioAcknowledgement(clientControlEvents, client, frame.chunkSeq());
                        return;
                    }
                    if (!(outcome instanceof LiveFrameOutcome.Accepted)) {
                        rejectedFrames.increment();
                        sink.error(new ClientFrameException(
                                "live audio sequence, ownership, or session state is invalid"));
                        return;
                    }
                    acceptedFrames.increment();
                    audioSent.set(true);
                    transcriptWindow.append(frame, record.sampleRateHz(), record.channels());
                    if (speechmatics == null) {
                        sink.next(upstream.binaryMessage(factory ->
                                factory.wrap(frame.toFloat32LittleEndian())));
                    } else {
                        speechmaticsFrameCount.incrementAndGet();
                        acceptedSamples.addAndGet(frame.pcm16().length / Short.BYTES);
                        sink.next(upstream.binaryMessage(factory -> factory.wrap(frame.pcm16())));
                    }
                    emitAudioAcknowledgement(clientControlEvents, client, frame.chunkSeq());
                })
                // The AI endpoint can spend minutes loading pinned models. Do not admit,
                // account or forward any desktop audio until it proves the exact source
                // range protocol is ready; otherwise early microphone frames are lost.
                .delaySubscription(upstreamReady.asMono().timeout(readyTimeout));

        final Flux<WebSocketMessage> speechmaticsTerminalMessages = speechmatics == null
                ? Flux.empty()
                : Mono.defer(() -> speechmatics.endMessageWhenAcknowledged(
                                speechmaticsFrameCount.get(),
                                Duration.ofMillis(properties.getDirectStt()
                                        .getSpeechmatics()
                                        .getAudioAckTimeoutMs())))
                        .switchIfEmpty(Mono.error(new TerminalDrainException()))
                        .doOnNext(ignored -> log.info(
                                "Speechmatics EOF enqueued sessionId={} frames={} acknowledged={}",
                                record.sessionId(),
                                speechmaticsFrameCount.get(),
                                speechmatics.lastAcknowledgedAudioSequence()))
                        .map(upstream::textMessage)
                        .doOnError(error -> log.warn(
                                "Speechmatics EOF creation failed err={} sessionId={} frames={}",
                                error.getClass().getSimpleName(),
                                record.sessionId(),
                                speechmaticsFrameCount.get()))
                        .flux();
        final Mono<Void> ingestClientFrames = admittedClientFrames
                .concatMap(message -> {
                    final Flux<WebSocketMessage> messages = speechmatics != null
                                    && isSpeechmaticsUploadTerminalMarker(message)
                            ? speechmaticsTerminalMessages
                            : Flux.just(message);
                    return messages
                            .doOnNext(outboundMessage -> emitUpstreamUploadFrame(
                                    upstreamUploadFrames, outboundMessage))
                            .then(Mono.fromRunnable(() -> {
                                if (eofSent.get()) {
                                    completeUpstreamUpload(upstreamUploadFrames);
                                }
                            }));
                }, 1)
                .then()
                .doOnSuccess(ignored -> upstreamUploadFrames.tryEmitComplete())
                .doOnError(upstreamUploadFrames::tryEmitError)
                // Keep the desktop receive leg subscribed while the provider drains. Only
                // terminal delivery to the desktop is allowed to cancel this subscription.
                .takeUntilOther(drainedRelayed.asMono())
                .doFinally(signal -> clientControlEvents.tryEmitComplete());

        final Flux<CopiedUpstreamMessage> normalizedUpstreamEvents = upstream.receive()
                .limitRate(1)
                // Detach from reactor-netty's frame before the asynchronous persistence
                // boundary. Keeping even a reference to the framework-owned message inside
                // concatMap lets cancellation/discard race its own release lifecycle.
                .map(this::copyUpstreamMessage)
                .concatMap(message -> normalizeUpstreamMessage(
                        message, speechmatics, acceptedSamples.get()), 1);
        final Flux<RelayedEvent> upstreamRelayedEvents = normalizedUpstreamEvents
                .concatMap(message -> relayUpstreamMessage(
                        client,
                        message,
                        record,
                        correlationId,
                        readyObserved,
                        eofSent,
                        eofAckObserved,
                        drainedObserved,
                        errorObserved,
                        speechmaticsFrameCount,
                        transcriptWindow), 1)
                .takeUntil(RelayedEvent::terminal)
                // Admit desktop audio only after the validated ready event has entered
                // the client's outbound WebSocket publisher. Emitting the gate while
                // parsing ready lets a concurrently produced audio_ack overtake ready.
                .concatMap(event -> event.ready()
                        ? Flux.concat(
                                Mono.just(event),
                                Mono.defer(() -> {
                                    upstreamReady.tryEmitEmpty();
                                    return Mono.empty();
                                }))
                        : Mono.just(event), 1);
        // Spring permits one send publisher per session. Merge gateway-owned control
        // acknowledgements into the same outbound publisher as upstream STT events.
        // The post-merge terminal guard also cancels the acknowledgement source when
        // an upstream error/drained event ends the connection.
        final Flux<RelayedEvent> relayedEvents = Flux
                .merge(clientControlEvents.asFlux(), upstreamRelayedEvents)
                .takeUntil(RelayedEvent::terminal);

        // Faz 24 (gitops#3435 dilim-3): the user dictionary rides in
        // StartRecognition, which Speechmatics accepts once and only before any
        // audio. It therefore CANNOT come from the client's mid-stream context
        // frame: client frames are admitted only after `upstreamReady`, which
        // itself waits for RecognitionStarted — the terms would always arrive
        // too late (proven by a deadlocking loopback run). The dictionary is a
        // property of the session, so it travels in the session-start request
        // and is read from the record here.
        final Flux<WebSocketMessage> outbound = speechmatics == null
                ? upstreamUploadFrames.asFlux()
                : Flux.concat(
                        Mono.just(upstream.textMessage(speechmatics.startMessage(
                                record.sampleRateHz(), record.contextTerms()))),
                        upstreamUploadFrames.asFlux());
        final Mono<Void> upstreamWrite = upstream.send(outbound)
                .doOnSuccess(ignored -> {
                    if (speechmatics != null && eofSent.get()) {
                        log.info(
                                "Speechmatics terminal upload flushed sessionId={} frames={}",
                                record.sessionId(),
                                speechmaticsFrameCount.get());
                    }
                })
                .doOnError(error -> {
                    if (speechmatics != null && eofSent.get()) {
                        log.warn(
                                "Speechmatics terminal upload failed err={} sessionId={} frames={}",
                                error.getClass().getSimpleName(),
                                record.sessionId(),
                                speechmaticsFrameCount.get());
                    }
                })
                .doOnCancel(() -> {
                    if (speechmatics != null && eofSent.get()) {
                        log.warn(
                                "Speechmatics terminal upload cancelled before flush "
                                        + "sessionId={} frames={}",
                                record.sessionId(),
                                speechmaticsFrameCount.get());
                    }
                });
        final Mono<Void> upload = upstreamWrite
                .then(Mono.defer(() -> eofSent.get()
                        ? drainedRelayed.asMono()
                                .timeout(
                                        drainTimeout,
                                        Mono.error(new TerminalDrainException()))
                                .then()
                        : Mono.empty()));
        final Mono<Void> download = client.send(relayedEvents.map(RelayedEvent::message))
                .doOnSuccess(ignored -> {
                    if (drainedObserved.get()) {
                        drainedRelayed.tryEmitEmpty();
                    }
                })
                .then(Mono.defer(() -> {
                    if (errorObserved.get()) {
                        return Mono.error(new UpstreamTerminalException());
                    }
                    return eofSent.get() && !drainedObserved.get()
                            ? Mono.error(new TerminalDrainException())
                            : Mono.empty();
                }));
        // Upload flush, provider drain and desktop relay are a conjunction. No leg may win
        // by cancelling another before the provider-authoritative terminal reached the user.
        return Mono.when(upload, download, ingestClientFrames).then();
    }

    private static boolean isSpeechmaticsUploadTerminalMarker(
            final WebSocketMessage message) {
        return message.getType() == WebSocketMessage.Type.TEXT
                && SPEECHMATICS_UPLOAD_TERMINAL_MARKER.equals(
                        message.getPayloadAsText());
    }

    static void emitUpstreamUploadFrame(
            final Sinks.Many<WebSocketMessage> upstreamUploadFrames,
            final WebSocketMessage message) {
        final Sinks.EmitResult result = upstreamUploadFrames.tryEmitNext(message);
        if (result.isFailure()) {
            throw new UpstreamUploadException(result);
        }
    }

    private static void completeUpstreamUpload(
            final Sinks.Many<WebSocketMessage> upstreamUploadFrames) {
        final Sinks.EmitResult result = upstreamUploadFrames.tryEmitComplete();
        if (result.isFailure()) {
            throw new UpstreamUploadException(result);
        }
    }

    private void emitAudioAcknowledgement(
            final Sinks.Many<RelayedEvent> clientControlEvents,
            final WebSocketSession client,
            final long chunkSeq) {
        final WebSocketMessage acknowledgement = client.textMessage(
                "{\"type\":\"audio_ack\",\"chunk_seq\":" + chunkSeq + "}");
        final Sinks.EmitResult result = clientControlEvents.tryEmitNext(
                new RelayedEvent(acknowledgement, false, false, false));
        if (result.isFailure()) {
            throw new ClientFrameException(
                    "live audio acknowledgement buffer is unavailable");
        }
    }

    private Flux<CopiedUpstreamMessage> normalizeUpstreamMessage(
            final CopiedUpstreamMessage message,
            final SpeechmaticsLiveProtocolAdapter speechmatics,
            final long acceptedSamples) {
        if (speechmatics == null || !(message instanceof CopiedUpstreamMessage.Text text)) {
            return Flux.just(message);
        }
        return Flux.fromIterable(speechmatics.translate(text.value(), acceptedSamples))
                .map(CopiedUpstreamMessage.Text::new);
    }

    private Mono<RelayedEvent> relayUpstreamMessage(
            final WebSocketSession client,
            final CopiedUpstreamMessage message,
            final SessionRecord record,
            final String correlationId,
            final AtomicBoolean readyObserved,
            final AtomicBoolean eofSent,
            final AtomicBoolean eofAckObserved,
            final AtomicBoolean drainedObserved,
            final AtomicBoolean errorObserved,
            final AtomicLong speechmaticsFrameCount,
            final LiveTranscriptWindowAccumulator transcriptWindow) {
        if (message instanceof CopiedUpstreamMessage.Text textMessage) {
            final String event = textMessage.value();
            final UpstreamEvent parsed = parseUpstreamEvent(event);
            if (parsed instanceof UpstreamEvent.Loading) {
                if (readyObserved.get() || eofSent.get() || eofAckObserved.get()
                        || drainedObserved.get() || errorObserved.get()) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT loading event violates stream order"));
                }
            }
            if (parsed instanceof UpstreamEvent.Ready ready) {
                if (eofSent.get() || eofAckObserved.get() || drainedObserved.get()
                        || errorObserved.get() || !readyObserved.compareAndSet(false, true)) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT ready event violates stream order"));
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
            }
            if (parsed instanceof UpstreamEvent.EofAck) {
                if (!readyObserved.get() || !eofSent.get() || drainedObserved.get()
                        || errorObserved.get() || !eofAckObserved.compareAndSet(false, true)) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT eof_ack violates terminal event order"));
                }
                log.info(
                        "Speechmatics terminal acknowledged sessionId={} frames={}",
                        record.sessionId(),
                        speechmaticsFrameCount.get());
            }
            if (parsed instanceof UpstreamEvent.Partial) {
                if (!readyObserved.get() || eofAckObserved.get() || drainedObserved.get()
                        || errorObserved.get()) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT partial violates stream order"));
                }
            }
            if (parsed instanceof UpstreamEvent.Debug) {
                if (!readyObserved.get() || drainedObserved.get() || errorObserved.get()) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT debug event violates stream order"));
                }
            }
            if (parsed instanceof UpstreamEvent.Error) {
                if (drainedObserved.get() || !errorObserved.compareAndSet(false, true)) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT error event violates terminal order"));
                }
                return Mono.just(new RelayedEvent(client.textMessage(event), false, false, true));
            }
            if (parsed instanceof UpstreamEvent.Final finalEvent) {
                if (!readyObserved.get() || drainedObserved.get() || errorObserved.get()) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT final violates stream order"));
                }
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
                        .thenReturn(new RelayedEvent(
                                client.textMessage(event), false, false, false));
            }
            final boolean drained = parsed instanceof UpstreamEvent.Drained;
            if (drained) {
                if (!eofSent.get() || !eofAckObserved.get()
                        || !drainedObserved.compareAndSet(false, true)) {
                    return Mono.error(new IllegalArgumentException(
                            "live STT drained violates terminal event order"));
                }
                log.info(
                        "Speechmatics terminal drained sessionId={} frames={}",
                        record.sessionId(),
                        speechmaticsFrameCount.get());
            }
            return Mono.just(new RelayedEvent(
                    client.textMessage(event), drained, parsed instanceof UpstreamEvent.Ready, false));
        }
        if (message instanceof CopiedUpstreamMessage.Ping ping) {
            return Mono.just(new RelayedEvent(
                    client.pingMessage(factory -> factory.wrap(ping.payload())),
                    false,
                    false,
                    false));
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
                window.byteLength(),
                DirectSttTranscriptResultContext.Transport.WEBSOCKET,
                window.epoch());
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
            requireExactFields(root, Set.of(
                    "type", "sample_rate", "live_model", "final_model", "partial_mode",
                    "protocol", "capabilities", "supports_eof", "terminal_timeout_ms"));
            final JsonNode capabilities = root.path("capabilities");
            final JsonNode terminalTimeout = root.path("terminal_timeout_ms");
            if (!UPSTREAM_PROTOCOL.equals(root.path("protocol").asText())
                    || !"stable-v1".equals(root.path("partial_mode").asText())
                    || !root.path("supports_eof").isBoolean()
                    || !root.path("supports_eof").booleanValue()
                    || !root.path("sample_rate").isIntegralNumber()
                    || root.path("sample_rate").longValue() != 16_000L
                    || !root.path("live_model").isTextual()
                    || root.path("live_model").textValue().isBlank()
                    || !root.path("final_model").isTextual()
                    || root.path("final_model").textValue().isBlank()
                    || !hasSupportedCapabilities(capabilities)
                    || !terminalTimeout.isIntegralNumber()
                    || !terminalTimeout.canConvertToLong()
                    || terminalTimeout.longValue() < 1_000L
                    || terminalTimeout.longValue() > 120_000L) {
                throw new IllegalArgumentException(
                        "live STT ready event does not satisfy source range protocol");
            }
            return new UpstreamEvent.Ready(terminalTimeout.longValue());
        }
        if ("drained".equals(type)) {
            requireExactFields(root, Set.of("type"));
            return new UpstreamEvent.Drained();
        }
        if ("eof_ack".equals(type)) {
            requireExactFields(root, Set.of("type"));
            return new UpstreamEvent.EofAck();
        }
        if ("partial".equals(type)) {
            requireFieldsWithOptionalTimings(root, Set.of(
                    "type", "seq", "confirmed", "tentative", "elapsed_ms", "rms", "source"));
            final JsonNode sequence = root.path("seq");
            final JsonNode elapsed = root.path("elapsed_ms");
            final JsonNode rms = root.path("rms");
            if (!sequence.isIntegralNumber() || !sequence.canConvertToLong()
                    || sequence.longValue() < 0L
                    || !root.path("confirmed").isTextual()
                    || !root.path("tentative").isTextual()
                    || !root.path("source").isTextual()
                    || root.path("source").textValue().isBlank()
                    || !elapsed.isIntegralNumber() || !elapsed.canConvertToLong()
                    || elapsed.longValue() < 0L
                    || !isNonNegativeFiniteNumber(rms)) {
                throw new IllegalArgumentException("live STT partial event is invalid");
            }
            return new UpstreamEvent.Partial();
        }
        if ("loading".equals(type)) {
            requireExactFields(root, Set.of("type", "stage"));
            final String stage = root.path("stage").asText("");
            if (!("live_model".equals(stage) || "final_model".equals(stage))) {
                throw new IllegalArgumentException("live STT loading event is invalid");
            }
            return new UpstreamEvent.Loading();
        }
        if ("error".equals(type)) {
            requireExactFields(root, Set.of("type", "msg"));
            if (!root.path("msg").isTextual() || root.path("msg").textValue().isBlank()) {
                throw new IllegalArgumentException("live STT error event is invalid");
            }
            return new UpstreamEvent.Error();
        }
        if ("debug".equals(type)) {
            if (!root.path("event").isTextual() || root.path("event").textValue().isBlank()) {
                throw new IllegalArgumentException("live STT debug event is invalid");
            }
            return new UpstreamEvent.Debug();
        }
        if (!"final".equals(type)) {
            throw new IllegalArgumentException("live STT emitted unknown event type");
        }
        requireFieldsWithOptionalTimings(root, Set.of(
                "type", "seq", "text", "reason", "elapsed_ms", "rms",
                "source_start_sample", "source_end_sample"));
        final JsonNode sequence = root.path("seq");
        final JsonNode text = root.path("text");
        if (!sequence.isIntegralNumber() || !sequence.canConvertToLong()
                || sequence.longValue() < 0L
                || !text.isTextual() || text.textValue().isBlank()) {
            throw new IllegalArgumentException("live STT final event is incomplete");
        }
        final JsonNode elapsed = root.path("elapsed_ms");
        if (!elapsed.isIntegralNumber() || !elapsed.canConvertToLong()
                || elapsed.longValue() < 0L) {
            throw new IllegalArgumentException("live STT final elapsed_ms is invalid");
        }
        final JsonNode rms = root.path("rms");
        if (!isNonNegativeFiniteNumber(rms)) {
            throw new IllegalArgumentException("live STT final rms is invalid");
        }
        if (!root.path("reason").isTextual()
                || !root.path("reason").textValue().matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw new IllegalArgumentException("live STT final reason is invalid");
        }
        final String reason = root.path("reason").textValue();
        final JsonNode sourceStart = root.path("source_start_sample");
        final JsonNode sourceEnd = root.path("source_end_sample");
        if (!sourceStart.isIntegralNumber() || !sourceStart.canConvertToLong()
                || !sourceEnd.isIntegralNumber() || !sourceEnd.canConvertToLong()
                || sourceStart.longValue() < 0L
                || sourceEnd.longValue() <= sourceStart.longValue()) {
            throw new IllegalArgumentException("live STT final source sample range is invalid");
        }
        return new UpstreamEvent.Final(
                sequence.longValue(), text.textValue(), reason, elapsed.doubleValue(),
                sourceStart.longValue(), sourceEnd.longValue());
    }

    private static boolean hasSupportedCapabilities(final JsonNode capabilities) {
        if (!capabilities.isArray()
                || (capabilities.size() != 2 && capabilities.size() != 3)
                || !"eof".equals(capabilities.path(0).asText())
                || !UPSTREAM_PROTOCOL.equals(capabilities.path(1).asText())) {
            return false;
        }
        return capabilities.size() == 2
                || "context-v1".equals(capabilities.path(2).asText());
    }

    private static void requireExactFields(final JsonNode root, final Set<String> expected) {
        if (root.size() != expected.size()) {
            throw new IllegalArgumentException("live STT event fields do not match contract");
        }
        final var fields = root.fieldNames();
        while (fields.hasNext()) {
            if (!expected.contains(fields.next())) {
                throw new IllegalArgumentException("live STT event fields do not match contract");
            }
        }
    }

    /**
     * gitops#3419 RT-5 latency study: partial/final events may carry gateway
     * stage-timing fields. Optional because the internal live-stt lane does not
     * emit them; when present each must be an integral non-negative number.
     */
    private static final Set<String> STAGE_TIMING_FIELDS =
            Set.of("audio_sent_ms", "emitted_at_ms");

    private static void requireFieldsWithOptionalTimings(
            final JsonNode root, final Set<String> required) {
        int requiredSeen = 0;
        final var fields = root.fieldNames();
        while (fields.hasNext()) {
            final String field = fields.next();
            if (required.contains(field)) {
                requiredSeen += 1;
                continue;
            }
            if (!STAGE_TIMING_FIELDS.contains(field)) {
                throw new IllegalArgumentException("live STT event fields do not match contract");
            }
            final JsonNode value = root.path(field);
            if (!value.isIntegralNumber() || !value.canConvertToLong()
                    || value.longValue() < 0L) {
                throw new IllegalArgumentException("live STT stage timing field is invalid");
            }
        }
        if (requiredSeen != required.size()) {
            throw new IllegalArgumentException("live STT event fields do not match contract");
        }
    }

    private static boolean isNonNegativeFiniteNumber(final JsonNode value) {
        return value.isNumber()
                && Double.isFinite(value.doubleValue())
                && value.doubleValue() >= 0.0d;
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

    static final class TerminalDrainException extends RuntimeException {
        private TerminalDrainException() {
            super("live STT terminal drain did not complete");
        }
    }

    static final class UpstreamUploadException extends RuntimeException {
        private UpstreamUploadException(final Sinks.EmitResult result) {
            super("live STT upstream upload buffer is unavailable: " + result);
        }
    }

    private static final class UpstreamTerminalException extends RuntimeException {
        private UpstreamTerminalException() {
            super("live STT emitted a terminal error");
        }
    }

    private record RelayedEvent(
            WebSocketMessage message,
            boolean drained,
            boolean ready,
            boolean failed) {
        private boolean terminal() {
            return drained || failed;
        }
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

        record Loading() implements UpstreamEvent {
        }

        record Error() implements UpstreamEvent {
        }

        record Debug() implements UpstreamEvent {
        }
    }
}
