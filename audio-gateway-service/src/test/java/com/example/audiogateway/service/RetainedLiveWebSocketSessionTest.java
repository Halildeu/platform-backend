package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

class RetainedLiveWebSocketSessionTest {
    private final List<Disposable> attachments = new ArrayList<>();
    private VirtualTimeScheduler time;
    private RetainedLiveWebSocketSession logical;
    private AtomicBoolean providerCancelled;
    private AtomicBoolean evicted;

    @BeforeEach void setup() {
        time = VirtualTimeScheduler.create();
        providerCancelled = new AtomicBoolean();
        evicted = new AtomicBoolean();
        logical = new RetainedLiveWebSocketSession(new HandshakeInfo(
                URI.create("ws://fixture/stream"), new HttpHeaders(), Mono.empty(), null),
                new ObjectMapper(), time, Duration.ofSeconds(60), 65554, 4096, 262144,
                () -> evicted.set(true));
        logical.start(Mono.<Void>never().doOnCancel(() -> providerCancelled.set(true)));
    }
    @AfterEach void cleanup() {
        logical.evict();
        attachments.forEach(Disposable::dispose);
        time.dispose();
    }
    private void publish(String text) { logical.send(Mono.just(logical.textMessage(text))).block(); }
    private void ready() { publish("{\"type\":\"ready\",\"sample_rate\":16000}"); }
    private void finalEvent(long seq) { publish("{\"type\":\"final\",\"seq\":" + seq + ",\"text\":\"fixture\"}"); }
    private Probe attach(String epoch, long cursor) {
        Probe probe = probe(true);
        attachments.add(logical.attach(probe.socket, epoch, cursor).subscribe());
        return probe;
    }

    @Test void detachKeepsProviderAliveAndReplaysFinalsBeforeLiveTransition() {
        Probe first = attach(null, -1);
        ready(); finalEvent(0);
        first.inbound.tryEmitComplete();
        time.advanceTimeBy(Duration.ofSeconds(30));
        finalEvent(1);
        assertThat(providerCancelled).isFalse();
        Probe resumed = attach(logical.epoch(), 0);
        assertThat(resumed.events).hasSize(3);
        assertThat(resumed.events.get(0)).contains("recording-resume-v1", logical.epoch(), "\"replay_after\":0", "\"replay_through\":1");
        assertThat(resumed.events.get(1)).contains("\"type\":\"final\"", "\"seq\":1");
        assertThat(resumed.events.get(2)).contains("resume_complete", "\"replay_through\":1");
        finalEvent(2);
        assertThat(resumed.events.get(3)).contains("\"seq\":2");
        assertThat(resumed.events).noneMatch(value -> value.contains("\"seq\":0"));
        time.advanceTimeBy(Duration.ofSeconds(31));
        assertThat(providerCancelled).isFalse();
        assertThat(evicted).isFalse();
    }

    @Test void expiryCancelsProviderAndRejectsResumeWithoutCreatingNewSource() {
        Probe first = attach(null, -1);
        ready(); first.inbound.tryEmitComplete();
        time.advanceTimeBy(Duration.ofSeconds(60));
        assertThat(providerCancelled).isTrue();
        assertThat(evicted).isTrue();
        Probe resumed = attach(logical.epoch(), -1);
        assertThat(resumed.closed.get()).isEqualTo(CloseStatus.POLICY_VIOLATION);
        assertThat(resumed.events).isEmpty();
    }

    @Test void allocationWithoutStartOrSubscriptionIsEvicted() {
        AtomicBoolean orphanEvicted = new AtomicBoolean();
        RetainedLiveWebSocketSession orphan = new RetainedLiveWebSocketSession(
                logical.getHandshakeInfo(), new ObjectMapper(), time, Duration.ofSeconds(60),
                65554, 4096, 262144, () -> orphanEvicted.set(true));
        time.advanceTimeBy(Duration.ofSeconds(60));
        assertThat(orphanEvicted).isTrue();
        assertThat(orphan.isOpen()).isFalse();
    }

    @Test void missingInitialAttachmentHasBoundedProviderLifetime() {
        time.advanceTimeBy(Duration.ofSeconds(60));
        assertThat(providerCancelled).isTrue();
        assertThat(evicted).isTrue();
    }

    @Test void repeatedReplacementPurgesStaleInputBeforeProviderDemandReturns() {
        Probe first = attach(null, -1); ready();
        first.inbound.tryEmitNext(logical.textMessage("old one"));
        Probe second = attach(logical.epoch(), -1);
        second.inbound.tryEmitNext(logical.textMessage("old two"));
        Probe third = attach(logical.epoch(), -1);
        third.inbound.tryEmitNext(logical.textMessage("current"));
        final List<String> admitted = new ArrayList<>();
        attachments.add(logical.receive().subscribe(value -> admitted.add(value.getPayloadAsText())));
        assertThat(admitted).containsExactly("current");
        assertThat(providerCancelled).isFalse();
    }

    @Test void detachedTerminalReplayKeepsEofAcknowledgementBeforeDrained() {
        Probe first = attach(null, -1); ready();
        first.inbound.tryEmitComplete();
        publish("{\"type\":\"eof_ack\"}");
        finalEvent(0);
        publish("{\"type\":\"drained\"}");
        logical.finish(CloseStatus.NORMAL);
        Probe resumed = attach(logical.epoch(), -1);
        assertThat(resumed.events.subList(1, resumed.events.size())).containsExactly(
                "{\"type\":\"final\",\"seq\":0,\"text\":\"fixture\"}",
                "{\"type\":\"resume_complete\",\"source_epoch\":\"" + logical.epoch() + "\",\"replay_through\":0}",
                "{\"type\":\"eof_ack\"}", "{\"type\":\"drained\"}");
    }

    @Test void reconnectCannotExtendClosedReplayRetentionDeadline() {
        Probe first = attach(null, -1); ready();
        first.inbound.tryEmitComplete();
        publish("{\"type\":\"drained\"}");
        logical.finish(CloseStatus.NORMAL);
        time.advanceTimeBy(Duration.ofSeconds(40));
        assertThat(attach(logical.epoch(), -1).events.getLast()).contains("drained");
        time.advanceTimeBy(Duration.ofSeconds(20));
        assertThat(evicted).isTrue();
        assertThat(attach(logical.epoch(), -1).closed.get()).isEqualTo(CloseStatus.POLICY_VIOLATION);
    }

    @Test void stalledTerminalConsumerCannotExtendRetentionDeadline() {
        Probe first = attach(null, -1); ready(); first.inbound.tryEmitComplete();
        publish("{\"type\":\"drained\"}"); logical.finish(CloseStatus.NORMAL);
        time.advanceTimeBy(Duration.ofSeconds(40));
        Probe stalled = probe(false);
        attachments.add(logical.attach(stalled.socket, logical.epoch(), -1).subscribe());
        time.advanceTimeBy(Duration.ofSeconds(20));
        assertThat(evicted).isTrue();
        assertThat(stalled.closed.get()).isEqualTo(CloseStatus.NORMAL);
    }

    @Test void rejectsForeignFutureAndMissingCursorsWithoutDisturbingOwner() {
        Probe owner = attach(null, -1); ready(); finalEvent(0);
        assertThat(attach("foreign-epoch", 0).closed.get()).isEqualTo(CloseStatus.POLICY_VIOLATION);
        assertThat(attach(logical.epoch(), 1).closed.get()).isEqualTo(CloseStatus.POLICY_VIOLATION);
        assertThat(attach(null, -1).closed.get()).isEqualTo(CloseStatus.POLICY_VIOLATION);
        finalEvent(1);
        assertThat(owner.events.getLast()).contains("\"seq\":1");
        assertThat(providerCancelled).isFalse();
    }

    @Test void supersededSocketCannotFeedOldFramesOrExpireReplacement() {
        Probe old = attach(null, -1); ready();
        old.inbound.tryEmitNext(logical.textMessage("old frame"));
        Probe replacement = attach(logical.epoch(), -1);
        assertThat(old.closed.get()).isEqualTo(LiveSttWebSocketProxyHandler.SUPERSEDED);
        final List<String> admitted = new ArrayList<>();
        attachments.add(logical.receive().subscribe(value -> admitted.add(value.getPayloadAsText())));
        replacement.inbound.tryEmitNext(logical.textMessage("new frame"));
        old.inbound.tryEmitComplete();
        time.advanceTimeBy(Duration.ofSeconds(61));
        assertThat(admitted).containsExactly("new frame");
        assertThat(providerCancelled).isFalse();
        assertThat(evicted).isFalse();
    }

    @Test void historyEvictionRejectsTooOldCursorInsteadOfClaimingCompleteReplay() {
        Probe first = attach(null, -1); ready();
        for (int seq = 0; seq < 260; seq++) finalEvent(seq);
        first.inbound.tryEmitComplete();
        assertThat(attach(logical.epoch(), -1).closed.get()).isEqualTo(CloseStatus.POLICY_VIOLATION);
        Probe resumed = attach(logical.epoch(), 3);
        assertThat(resumed.events).hasSize(258);
        assertThat(resumed.events.get(1)).contains("\"seq\":4");
        assertThat(resumed.events.getLast()).contains("resume_complete", "259");
    }

    @Test void providerFailureWhileDetachedRemainsAvailableAfterReconnect() {
        Probe first = attach(null, -1); ready(); first.inbound.tryEmitComplete();
        publish("{\"type\":\"error\",\"msg\":\"SPEECHMATICS_BUFFER_ERROR\"}");
        logical.finish(CloseStatus.SERVER_ERROR);
        Probe resumed = attach(logical.epoch(), -1);
        assertThat(resumed.events.getLast()).contains("SPEECHMATICS_BUFFER_ERROR");
        assertThat(providerCancelled).isTrue();
    }

    @Test void slowPhysicalConsumerDoesNotCancelProvider() {
        Probe stalled = probe(false);
        attachments.add(logical.attach(stalled.socket, null, -1).subscribe());
        ready();
        for (int seq = 0; seq < 520; seq++) finalEvent(seq);
        assertThat(stalled.closed.get()).isEqualTo(CloseStatus.SERVER_ERROR);
        assertThat(providerCancelled).isFalse();
        Probe resumed = attach(logical.epoch(), 519);
        assertThat(resumed.events).hasSize(2);
        assertThat(resumed.events.getLast()).contains("resume_complete");
    }

    private Probe probe(boolean consumes) {
        WebSocketSession socket = mock(WebSocketSession.class);
        List<String> events = new ArrayList<>();
        Sinks.Many<WebSocketMessage> input = Sinks.many().unicast().onBackpressureBuffer();
        AtomicReference<CloseStatus> closed = new AtomicReference<>();
        when(socket.receive()).thenReturn(input.asFlux());
        when(socket.textMessage(anyString())).thenAnswer(call -> logical.textMessage(call.getArgument(0)));
        when(socket.send(any(Publisher.class))).thenAnswer(call -> consumes
                ? Flux.from(call.<Publisher<WebSocketMessage>>getArgument(0))
                    .doOnNext(value -> events.add(value.getPayloadAsText())).then()
                : Mono.never());
        when(socket.close(any(CloseStatus.class))).thenAnswer(call -> {
            closed.set(call.getArgument(0)); return Mono.empty();
        });
        return new Probe(socket, input, events, closed);
    }
    private record Probe(WebSocketSession socket, Sinks.Many<WebSocketMessage> inbound,
            List<String> events, AtomicReference<CloseStatus> closed) { }
}
