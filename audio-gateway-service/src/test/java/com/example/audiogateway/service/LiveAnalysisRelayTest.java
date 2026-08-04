package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.audiogateway.dto.TranscriptResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

/**
 * Faz 24 İ5 — the relay that makes decisions/actions visible DURING the
 * meeting: {@link LiveAnalyzeTrigger} must hand every successful analysis
 * body to {@link LiveAnalysisStreamHub} so subscribed viewers see it.
 *
 * <p>Before this slice the trigger read the response as
 * {@code toBodilessEntity()} — the analysis was produced and then discarded
 * at the gateway, so nothing could reach a viewer mid-meeting. These tests
 * fail against that behaviour.
 */
class LiveAnalysisRelayTest {

    private static final String ANALYSIS_JSON =
            "{\"schema_version\":\"5-adr0043\",\"summary\":\"Demo yarin\","
                    + "\"decisions\":[\"Demo yarin saat onda\"],"
                    + "\"action_items\":[\"Ali sunumu hazirlayacak\"],\"is_partial\":true}";

    private MockWebServer server;
    private WebClient client;
    private MeterRegistry meters;
    private LiveAnalysisStreamHub hub;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        client = WebClient.builder()
                .baseUrl(server.url("/").toString().replaceAll("/+$", ""))
                .build();
        meters = new SimpleMeterRegistry();
        hub = new LiveAnalysisStreamHub();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private static TranscriptResult resultWith(final String text) {
        return new TranscriptResult(text, "tr", 0.99, 1.0, 100.0, "m", "int8", "cpu", null);
    }

    private LiveAnalyzeTrigger trigger(final LiveAnalysisStreamHub relay) {
        return new LiveAnalyzeTrigger(client, 1, "", Duration.ofSeconds(5), meters, relay);
    }

    @Test
    void relaysTheAnalysisBodyToSubscribersOfThatMeeting() {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(ANALYSIS_JSON));

        final List<String> received = new CopyOnWriteArrayList<>();
        final Disposable sub = hub.subscribe("meeting-1").subscribe(received::add);
        try {
            trigger(hub).offer("meeting-1", resultWith("Demo yarin saat onda"));

            // The POST is fire-and-forget; await the relayed frame.
            final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (received.isEmpty() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(25);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            assertThat(received).containsExactly(ANALYSIS_JSON);
        } finally {
            sub.dispose();
        }
    }

    @Test
    void doesNotLeakOneMeetingsAnalysisToAnother() {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(ANALYSIS_JSON));

        final List<String> otherMeeting = new CopyOnWriteArrayList<>();
        final Disposable sub = hub.subscribe("meeting-OTHER").subscribe(otherMeeting::add);
        try {
            trigger(hub).offer("meeting-1", resultWith("Demo yarin saat onda"));
            try {
                Thread.sleep(500);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            assertThat(otherMeeting).isEmpty();
        } finally {
            sub.dispose();
        }
    }

    @Test
    void countsSuccessAndSurvivesWithoutAnyRelayConfigured() {
        server.enqueue(
                new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(ANALYSIS_JSON));

        // Pre-İ5 constructor (no hub) must keep working — the relay is additive.
        final LiveAnalyzeTrigger noRelay =
                new LiveAnalyzeTrigger(client, 1, "", Duration.ofSeconds(5), meters);
        assertThatCode(() -> noRelay.offer("meeting-1", resultWith("metin")))
                .doesNotThrowAnyException();

        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (meters.counter("audio_gw_live_analyze_publish_success_total").count() < 1
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(meters.counter("audio_gw_live_analyze_publish_success_total").count())
                .isEqualTo(1.0);
    }

    @Test
    void aFailedAnalysisPublishesNothing() {
        server.enqueue(new MockResponse().setResponseCode(500));

        final List<String> received = new CopyOnWriteArrayList<>();
        final Disposable sub = hub.subscribe("meeting-1").subscribe(received::add);
        try {
            trigger(hub).offer("meeting-1", resultWith("metin"));
            try {
                Thread.sleep(500);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            assertThat(received).isEmpty();
            assertThat(meters.counter("audio_gw_live_analyze_publish_error_total").count())
                    .isEqualTo(1.0);
        } finally {
            sub.dispose();
        }
    }

    @Test
    void publishWithoutSubscribersIsANoOpAndDoesNotRetainMeetings() {
        hub.publish("meeting-nobody-watching", ANALYSIS_JSON);
        assertThat(hub.activeMeetings()).isZero();
    }

    @Test
    void subscriberSeesFramesPublishedAfterItConnects() {
        StepVerifier.create(hub.subscribe("meeting-2").take(1))
                .then(() -> hub.publish("meeting-2", ANALYSIS_JSON))
                .expectNext(ANALYSIS_JSON)
                .verifyComplete();
    }

    @Test
    void hubNeverThrowsOnDegenerateInput() {
        assertThatCode(
                        () -> {
                            hub.publish(null, ANALYSIS_JSON);
                            hub.publish("", ANALYSIS_JSON);
                            hub.publish("  ", ANALYSIS_JSON);
                            hub.publish("meeting-3", null);
                            hub.publish("meeting-3", "");
                        })
                .doesNotThrowAnyException();
        assertThat(hub.activeMeetings()).isZero();
    }
}
