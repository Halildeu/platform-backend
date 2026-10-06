package com.example.audiogateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

/**
 * One recording-owned provider transport. Physical sockets attach to this session;
 * their cancellation never cancels provider receive or final persistence.
 * All state and unsafe-sink emissions are serialized by this object's monitor.
 * Payloads are bounded heap copies; no Netty-owned DataBuffer survives its callback.
 */
final class RetainedLiveWebSocketSession implements WebSocketSession {
    static final String PROTOCOL = "recording-resume-v1";
    private static final int FINAL_HISTORY_COUNT = 256;
    private static final int FINAL_HISTORY_BYTES = 1024 * 1024;
    private static final int OUTBOUND_BYTES = 2 * 1024 * 1024;
    private static final int OUTBOUND_COUNT = 512;
    private final String epoch = UUID.randomUUID().toString();
    private final HandshakeInfo handshake;
    private final ObjectMapper mapper;
    private final Scheduler scheduler;
    private final Duration lease;
    private final Runnable onEvicted;
    private final int maxAudioBytes;
    private final int maxControlBytes;
    private final int maxEventBytes;
    private final Map<String, Object> attributes = new HashMap<>();
    private final ArrayBlockingQueue<Inbound> inputQueue = new ArrayBlockingQueue<>(2);
    private final Sinks.Many<Inbound> inbound = Sinks.unsafe().many().unicast()
            .onBackpressureBuffer(inputQueue);
    private final Sinks.One<Void> terminated = Sinks.one();
    private final Sinks.One<CloseStatus> closedStatus = Sinks.one();
    private final Disposable.Swap provider = Disposables.swap();
    private final ArrayDeque<FinalEvent> history = new ArrayDeque<>();
    private final ArrayDeque<Inbound> pendingInputs = new ArrayDeque<>();
    private long generation;
    private long lastFinal = -1L;
    private long historyFloor = -1L;
    private int historyBytes;
    private String ready;
    private String terminal;
    private String eofAcknowledgement;
    private Attachment attached;
    private Disposable expiry;
    private boolean started;
    private boolean everAttached;
    private boolean closed;
    private long closedAtMillis;
    private boolean evicted;
    private CloseStatus endStatus = CloseStatus.NORMAL;

    RetainedLiveWebSocketSession(HandshakeInfo handshake, ObjectMapper mapper, Scheduler scheduler,
            Duration lease, int maxAudioBytes, int maxControlBytes, int maxEventBytes, Runnable onEvicted) {
        this.handshake = handshake;
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.lease = lease;
        this.maxAudioBytes = maxAudioBytes;
        this.maxControlBytes = maxControlBytes;
        this.maxEventBytes = maxEventBytes;
        this.onEvicted = onEvicted;
        // Allocation itself owns a lease, even if an HTTP upgrade is cancelled
        // before the attach publisher gets subscribed or provider start runs.
        scheduleExpiry(generation);
    }

    synchronized void start(Mono<Void> work) {
        if (started || evicted) return;
        started = true;
        if (attached == null) scheduleExpiry(generation);
        provider.update(work.takeUntilOther(terminated.asMono()).subscribe(
                ignored -> { }, error -> finish(CloseStatus.SERVER_ERROR), () -> finish(CloseStatus.NORMAL)));
    }

    /** The caller authenticates tenant, owner, recording state before every attach. */
    Mono<Void> attach(WebSocketSession socket, String requestedEpoch, long afterFinal) {
        return Mono.defer(() -> {
            final Attachment attachment;
            synchronized (this) {
                if (evicted || (!everAttached && requestedEpoch != null)
                        || (everAttached && !epoch.equals(requestedEpoch))
                        || afterFinal < historyFloor || afterFinal > lastFinal
                        || (!everAttached && afterFinal != -1L)) {
                    return socket.close(CloseStatus.POLICY_VIOLATION);
                }
                if (expiry != null) expiry.dispose();
                final Attachment previous = attached;
                attachment = new Attachment(++generation, socket);
                attached = attachment;
                everAttached = true;
                if (previous != null) {
                    previous.stop.tryEmitEmpty();
                    discardInputs(previous.generation);
                    previous.socket.close(LiveSttWebSocketProxyHandler.SUPERSEDED)
                            .onErrorResume(error -> Mono.empty()).subscribe();
                }
                if (ready != null) {
                    // Snapshot and subscription switch are under the same lock as publish.
                    offer(attachment, resumeReady(afterFinal, lastFinal));
                    for (FinalEvent event : history) {
                        if (event.sequence > afterFinal) offer(attachment, event.text);
                    }
                    offer(attachment, resumeComplete(lastFinal));
                }
                if (eofAcknowledgement != null) offer(attachment, eofAcknowledgement);
                if (terminal != null) offer(attachment, terminal);
                if (closed) {
                    attachment.events.tryEmitComplete();
                    // A stalled terminal consumer must not cancel the absolute cache TTL.
                    scheduleExpiry(generation);
                }
            }
            final Mono<Void> read = socket.receive().limitRate(1)
                    .map(message -> copyInbound(message, attachment.generation))
                    .concatMap(message -> accept(attachment, message), 1).then();
            final Mono<Void> write = socket.send(attachment.events.asFlux()
                    .map(value -> {
                        synchronized (this) { attachment.queuedBytes -= bytes(value); }
                        return socket.textMessage(value);
                    }));
            return Mono.firstWithSignal(read, write)
                    .takeUntilOther(attachment.stop.asMono())
                    .onErrorResume(error -> Mono.empty())
                    .doFinally(signal -> detach(attachment));
        });
    }

    private Inbound copyInbound(WebSocketMessage message, long sourceGeneration) {
        final int limit = message.getType() == WebSocketMessage.Type.BINARY ? maxAudioBytes : maxControlBytes;
        if ((message.getType() != WebSocketMessage.Type.BINARY && message.getType() != WebSocketMessage.Type.TEXT)
                || message.getPayload().readableByteCount() > limit) {
            throw new IllegalArgumentException("Invalid retained live frame");
        }
        final byte[] bytes = new byte[message.getPayload().readableByteCount()];
        message.getPayload().read(bytes);
        return new Inbound(sourceGeneration,
                new WebSocketMessage(message.getType(), bufferFactory().wrap(bytes)), Sinks.one());
    }

    private synchronized Mono<Void> accept(Attachment source, Inbound message) {
        if (attached != source || closed || evicted) return Mono.empty();
        pendingInputs.add(message);
        if (inbound.tryEmitNext(message).isFailure()) {
            pendingInputs.remove(message);
            finish(CloseStatus.SERVER_ERROR);
            return Mono.error(new IllegalStateException("Retained input unavailable"));
        }
        return message.consumed.asMono();
    }

    private synchronized void consumed(Inbound message) {
        pendingInputs.remove(message);
        message.consumed.tryEmitEmpty();
    }

    private synchronized boolean current(Inbound message) {
        return !closed && attached != null && attached.generation == message.generation;
    }

    private synchronized void detach(Attachment source) {
        if (attached != source) return;
        attached = null;
        // Fence a stale physical receive before releasing any pending input gate.
        discardInputs(source.generation);
        scheduleExpiry(generation);
    }

    private void discardInputs(long obsoleteGeneration) {
        inputQueue.removeIf(input -> input.generation == obsoleteGeneration);
        final var abandoned = pendingInputs.stream().filter(input -> input.generation == obsoleteGeneration).toList();
        pendingInputs.removeAll(abandoned);
        for (Inbound input : abandoned) input.consumed.tryEmitEmpty();
    }

    private void scheduleExpiry(long expectedGeneration) {
        if (expiry != null) expiry.dispose();
        final long delay = closed ? Math.max(0L, closedAtMillis + lease.toMillis()
                - scheduler.now(TimeUnit.MILLISECONDS)) : lease.toMillis();
        expiry = scheduler.schedule(() -> {
            synchronized (this) {
                if (generation != expectedGeneration || (!closed && attached != null)) return;
                evict();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void publish(WebSocketMessage message) {
        if (closed || evicted) return;
        // Provider ping frames have no transcript identity and are not replayed.
        if (message.getType() != WebSocketMessage.Type.TEXT) return;
        final String value = message.getPayloadAsText();
        if (bytes(value) > maxEventBytes) throw new IllegalArgumentException("Retained event exceeds bound");
        final ObjectNode event;
        try { event = (ObjectNode) mapper.readTree(value); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid retained event", error); }
        switch (event.path("type").asText()) {
            case "ready" -> {
                ready = value;
                final Attachment target = attached;
                if (target != null) {
                    offer(target, resumeReady(-1L, lastFinal));
                    offer(target, resumeComplete(lastFinal));
                }
                return;
            }
            case "final" -> {
                long sequence = event.path("seq").asLong(-1L);
                if (sequence != lastFinal + 1L) throw new IllegalStateException("Non-contiguous retained final");
                lastFinal = sequence;
                history.add(new FinalEvent(sequence, value));
                historyBytes += bytes(value);
                while (history.size() > FINAL_HISTORY_COUNT || historyBytes > FINAL_HISTORY_BYTES) {
                    FinalEvent removed = history.removeFirst();
                    historyBytes -= bytes(removed.text);
                    historyFloor = removed.sequence;
                }
            }
            case "eof_ack" -> eofAcknowledgement = value;
            case "error", "drained" -> terminal = value;
            default -> { }
        }
        if (attached != null) offer(attached, value);
    }

    private String resumeReady(long after, long through) {
        try {
            ObjectNode event = (ObjectNode) mapper.readTree(ready);
            event.put("resume_protocol", PROTOCOL).put("source_epoch", epoch)
                    .put("replay_after", after).put("replay_through", through);
            return event.toString();
        } catch (Exception error) { throw new IllegalStateException("Invalid cached ready", error); }
    }

    private String resumeComplete(long through) {
        return mapper.createObjectNode().put("type", "resume_complete")
                .put("source_epoch", epoch).put("replay_through", through).toString();
    }

    private void offer(Attachment destination, String value) {
        if (destination == null || attached != destination) return;
        final int size = bytes(value);
        if (destination.queuedBytes + size > OUTBOUND_BYTES) {
            dropSlowAttachment(destination);
            return;
        }
        destination.queuedBytes += size;
        if (destination.events.tryEmitNext(value).isFailure()) {
            destination.queuedBytes -= size;
            dropSlowAttachment(destination);
        }
    }

    private void dropSlowAttachment(Attachment source) {
        // Persisted finals remain replayable; never cancel the provider for a slow phone.
        source.stop.tryEmitEmpty();
        source.socket.close(CloseStatus.SERVER_ERROR).onErrorResume(error -> Mono.empty()).subscribe();
        detach(source);
    }

    synchronized void finish(CloseStatus status) {
        if (closed || evicted) return;
        closed = true;
        closedAtMillis = scheduler.now(TimeUnit.MILLISECONDS);
        endStatus = terminal == null ? CloseStatus.SERVER_ERROR : status;
        if (!CloseStatus.NORMAL.equals(endStatus) && terminal == null) {
            terminal = "{\"type\":\"error\",\"msg\":\"LIVE_RESUME_UNAVAILABLE\"}";
            if (attached != null) offer(attached, terminal);
        }
        inbound.tryEmitComplete();
        final var abandonedInputs = List.copyOf(pendingInputs);
        pendingInputs.clear();
        inputQueue.clear();
        for (Inbound input : abandonedInputs) input.consumed.tryEmitEmpty();
        if (attached != null) attached.events.tryEmitComplete();
        terminated.tryEmitEmpty();
        closedStatus.tryEmitValue(endStatus);
        provider.dispose();
        scheduleExpiry(generation);
    }

    synchronized void evict() {
        if (evicted) return;
        finish(CloseStatus.SERVER_ERROR);
        evicted = true;
        if (expiry != null) expiry.dispose();
        if (attached != null) {
            final Attachment expired = attached;
            attached = null;
            expired.stop.tryEmitEmpty();
            expired.socket.close(endStatus).onErrorResume(error -> Mono.empty()).subscribe();
        }
        history.clear(); historyBytes = 0; ready = null; terminal = null; eofAcknowledgement = null;
        onEvicted.run();
    }

    String epoch() { return epoch; }
    Mono<Void> termination() { return terminated.asMono(); }
    @Override public String getId() { return epoch; }
    @Override public HandshakeInfo getHandshakeInfo() { return handshake; }
    @Override public DataBufferFactory bufferFactory() { return DefaultDataBufferFactory.sharedInstance; }
    @Override public Map<String, Object> getAttributes() { return attributes; }
    @Override public Flux<WebSocketMessage> receive() {
        return inbound.asFlux().concatMap(input -> Flux.concat(
                Mono.<WebSocketMessage>create(sink -> {
                    synchronized (this) {
                        if (current(input)) sink.success(input.message); else sink.success();
                    }
                }),
                Mono.defer(() -> { consumed(input); return Mono.empty(); })), 1);
    }
    @Override public Mono<Void> send(Publisher<WebSocketMessage> messages) {
        return Flux.from(messages).doOnNext(this::publish).then();
    }
    @Override public synchronized boolean isOpen() { return !closed && !evicted; }
    @Override public Mono<Void> close(CloseStatus status) { return Mono.fromRunnable(() -> finish(status)); }
    @Override public Mono<CloseStatus> closeStatus() { return closedStatus.asMono(); }
    @Override public WebSocketMessage textMessage(String value) {
        return new WebSocketMessage(WebSocketMessage.Type.TEXT, bufferFactory().wrap(value.getBytes(StandardCharsets.UTF_8)));
    }
    @Override public WebSocketMessage binaryMessage(Function<DataBufferFactory, DataBuffer> factory) {
        return new WebSocketMessage(WebSocketMessage.Type.BINARY, factory.apply(bufferFactory()));
    }
    @Override public WebSocketMessage pingMessage(Function<DataBufferFactory, DataBuffer> factory) {
        return new WebSocketMessage(WebSocketMessage.Type.PING, factory.apply(bufferFactory()));
    }
    @Override public WebSocketMessage pongMessage(Function<DataBufferFactory, DataBuffer> factory) {
        return new WebSocketMessage(WebSocketMessage.Type.PONG, factory.apply(bufferFactory()));
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private record FinalEvent(long sequence, String text) { }
    private record Inbound(long generation, WebSocketMessage message, Sinks.One<Void> consumed) { }
    private static final class Attachment {
        final long generation;
        final WebSocketSession socket;
        final Sinks.One<Void> stop = Sinks.one();
        final Sinks.Many<String> events = Sinks.unsafe().many().unicast()
                .onBackpressureBuffer(new ArrayBlockingQueue<>(OUTBOUND_COUNT));
        int queuedBytes;
        Attachment(long generation, WebSocketSession socket) { this.generation = generation; this.socket = socket; }
    }
}
