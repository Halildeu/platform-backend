package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.service.AudioChunkDispatcher.SessionDiscardCommand;
import com.example.audiogateway.service.AudioChunkDispatcher.SessionFinishCommand;
import com.example.audiogateway.service.DirectSttForwardingDispatcher.ForwardTask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

/**
 * #3746 BE-D4 — attribution finish notifier contract.
 *
 * <p>Direction: the finish POST fires exactly once, only after the finish signal AND a
 * drained outstanding-forward count, with expectedSampleCount counting ONLY successful
 * forwards; discard and disabled paths must never call live-stt.
 */
class DirectSttAttributionFinishNotifierTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

    private record CapturedRequest(String uri, JsonNode body) { }

    private WebClient capturingWebClient() {
        return WebClient.builder()
                .exchangeFunction(request -> {
                    final MockClientHttpRequest sink =
                            new MockClientHttpRequest(request.method(), request.url());
                    return request.writeTo(sink,
                            org.springframework.web.reactive.function.client.ExchangeStrategies.withDefaults())
                            .then(Mono.defer(() -> {
                                try {
                                    final String body = sink.getBodyAsString().block();
                                    requests.add(new CapturedRequest(
                                            request.url().toString(),
                                            body == null || body.isEmpty()
                                                    ? JSON.nullNode()
                                                    : JSON.readTree(body)));
                                } catch (final Exception ex) {
                                    throw new IllegalStateException(ex);
                                }
                                return Mono.just(ClientResponse
                                        .create(HttpStatus.ACCEPTED)
                                        .build());
                            }));
                })
                .build();
    }

    private AudioGatewayProperties.DirectStt config(final boolean enabled) {
        final AudioGatewayProperties.DirectStt cfg = new AudioGatewayProperties.DirectStt();
        cfg.setTranscribeUrl("https://10.99.0.2:8000/transcribe");
        cfg.getAttributionFinish().setEnabled(enabled);
        return cfg;
    }

    private DirectSttAttributionFinishNotifier notifier(final boolean enabled) {
        return new DirectSttAttributionFinishNotifier(
                capturingWebClient(), config(enabled), new SimpleMeterRegistry());
    }

    private static ForwardTask task(
            final String sessionId, final long epoch, final int lengthBytes) {
        return new ForwardTask(
                "pcm".getBytes(StandardCharsets.UTF_8), epoch, sessionId, 42L, 7L,
                3L, 0L, 4L, 1_000L, 5_000, "window_full",
                "9b2c5a89-f39a-47da-a33d-a2859b468e8b", "dev-1", "tr", "internal",
                "PCM16", 16_000, 1, "corr-1", "sha256:abc", lengthBytes, null);
    }

    private static SessionFinishCommand finishCommand(final String sessionId) {
        return new SessionFinishCommand(sessionId, 42L, 7L, "corr-1");
    }

    @Test
    void firesOnceAfterFinishAndDrainWithSuccessfulSamplesOnly() {
        final DirectSttAttributionFinishNotifier notifier = notifier(true);
        final ForwardTask first = task("SES-1", 3L, 160_000);
        final ForwardTask second = task("SES-1", 3L, 64_000);
        notifier.onForwardStart(first);
        notifier.onForwardStart(second);
        notifier.onForwardComplete(first, true);
        notifier.onSessionFinished(finishCommand("SES-1"));
        // One forward still outstanding: nothing may fire yet.
        assertThat(requests).isEmpty();
        notifier.onForwardComplete(second, false); // failed forward: not counted
        assertThat(requests).hasSize(1);
        final CapturedRequest sent = requests.get(0);
        assertThat(sent.uri()).isEqualTo(
                "https://10.99.0.2:8000/session/SES-1:3/finish");
        assertThat(sent.body().get("schema").asText()).isEqualTo("liveSttSessionFinish.v1");
        assertThat(sent.body().get("tenantId").asText()).isEqualTo("42");
        assertThat(sent.body().get("meetingId").asText())
                .isEqualTo("9b2c5a89-f39a-47da-a33d-a2859b468e8b");
        assertThat(sent.body().get("sourceSessionId").asText()).isEqualTo("SES-1");
        assertThat(sent.body().get("transportEpoch").asLong()).isEqualTo(3L);
        // Only the successful 160_000-byte window counts: 80_000 samples.
        assertThat(sent.body().get("expectedSampleCount").asLong()).isEqualTo(80_000L);
        assertThat(notifier.trackedSessions()).isZero();
    }

    @Test
    void firesWhenFinishArrivesAfterAllForwardsCompleted() {
        final DirectSttAttributionFinishNotifier notifier = notifier(true);
        final ForwardTask only = task("SES-2", 0L, 32_000);
        notifier.onForwardStart(only);
        notifier.onForwardComplete(only, true);
        assertThat(requests).isEmpty();
        notifier.onSessionFinished(finishCommand("SES-2"));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).body().get("expectedSampleCount").asLong())
                .isEqualTo(16_000L);
    }

    @Test
    void underOneSecondOfForwardedAudioSkipsTheCall() {
        final DirectSttAttributionFinishNotifier notifier = notifier(true);
        final ForwardTask tiny = task("SES-3", 0L, 2_000); // 1000 samples
        notifier.onForwardStart(tiny);
        notifier.onForwardComplete(tiny, true);
        notifier.onSessionFinished(finishCommand("SES-3"));
        assertThat(requests).isEmpty();
        assertThat(notifier.trackedSessions()).isZero();
    }

    @Test
    void discardDropsTrackingWithoutCalling() {
        final DirectSttAttributionFinishNotifier notifier = notifier(true);
        final ForwardTask only = task("SES-4", 1L, 160_000);
        notifier.onForwardStart(only);
        notifier.onForwardComplete(only, true);
        notifier.onSessionDiscarded(new SessionDiscardCommand("SES-4", 42L, 7L, "corr-1"));
        notifier.onSessionFinished(finishCommand("SES-4"));
        assertThat(requests).isEmpty();
        assertThat(notifier.trackedSessions()).isZero();
    }

    @Test
    void disabledNotifierTracksNothingAndNeverCalls() {
        final DirectSttAttributionFinishNotifier notifier = notifier(false);
        final ForwardTask only = task("SES-5", 0L, 160_000);
        notifier.onForwardStart(only);
        notifier.onForwardComplete(only, true);
        notifier.onSessionFinished(finishCommand("SES-5"));
        assertThat(requests).isEmpty();
        assertThat(notifier.trackedSessions()).isZero();
    }

    @Test
    void finishForUnknownSessionIsANoOp() {
        final DirectSttAttributionFinishNotifier notifier = notifier(true);
        notifier.onSessionFinished(finishCommand("SES-UNKNOWN"));
        assertThat(requests).isEmpty();
    }

    @Test
    void enabledWithUnderivableFinishUrlFailsClosedAtConstruction() {
        final AudioGatewayProperties.DirectStt cfg = new AudioGatewayProperties.DirectStt();
        cfg.setTranscribeUrl("https://10.99.0.2:8000/other-path");
        cfg.getAttributionFinish().setEnabled(true);
        assertThatThrownBy(() -> new DirectSttAttributionFinishNotifier(
                capturingWebClient(), cfg, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transcribe-url");
    }
}
