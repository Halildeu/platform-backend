package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.example.audiogateway.dto.TranscriptResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Unit-level tests for {@link LiveAnalyzeTrigger} — the segment-window
 * aggregator that fires cadenced /analyze/live POSTs.
 *
 * <p>Covers the wire (POST body shape, sequence monotonicity) via
 * MockWebServer, and the guarantees the class promises (blank inputs
 * drop, missing meeting_id drops, error swallowing).
 */
class LiveAnalyzeTriggerTest {

    private MockWebServer server;
    private WebClient client;
    private MeterRegistry meters;

    @BeforeEach
    void start() throws Exception {
        server = new MockWebServer();
        server.start();
        client = WebClient.builder()
                .baseUrl(server.url("/").toString().replaceAll("/+$", ""))
                .build();
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private static TranscriptResult resultWith(final String text) {
        return new TranscriptResult(text, "tr", 0.99, 1.0, 100.0, "m", "int8", "cpu", null);
    }

    private double counter(final String name) {
        return meters.counter(name).count();
    }

    @Test
    void sentenceModeWaitsForTheSentenceThenPublishesWithoutTheWordWindowDelay() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        try (var trigger = new LiveAnalyzeTrigger(client, 2, "", Duration.ofSeconds(2),
                meters, null, Duration.ZERO, Duration.ofSeconds(5), true)) {
            for (String word : new String[] {"Mehmet", "bütçe", "raporunu", "hazırlayacak"}) {
                trigger.offer("live-sentence", resultWith(word));
            }
            assertThat(server.takeRequest(150, TimeUnit.MILLISECONDS)).isNull();
            trigger.offer("live-sentence", resultWith("."));
            assertThat(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8())
                    .contains("Mehmet bütçe raporunu hazırlayacak .");
        }
    }

    @Test
    void incrementalCursorIsSentOnlyToItsOwnMeetingAndClearedForOldServers() throws Exception {
        final String cursor = "{\"source_length\":25,\"source_sha256\":\"" + "a".repeat(64)
                + "\",\"active_indices\":[0]}";
        server.enqueue(new MockResponse().setBody("{\"live_cursor\":" + cursor + "}"));
        server.enqueue(new MockResponse().setBody("{}"));
        server.enqueue(new MockResponse().setBody("{}"));
        server.enqueue(new MockResponse().setBody("{}"));
        try (var trigger = new LiveAnalyzeTrigger(client, 1, "", Duration.ofSeconds(2), meters)) {
            trigger.offer("one", resultWith("First task."));
            assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(counter("audio_gw_live_analyze_publish_success_total")).isEqualTo(1));
            trigger.offer("two", resultWith("Other meeting."));
            assertThat(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8())
                    .doesNotContain("previous_live_cursor");
            trigger.offer("one", resultWith("Next sentence."));
            assertThat(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8())
                    .contains("previous_live_cursor", "source_sha256");
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(counter("audio_gw_live_analyze_publish_success_total")).isEqualTo(3));
            trigger.offer("one", resultWith("Final sentence."));
            assertThat(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8())
                    .doesNotContain("previous_live_cursor");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{}",
            "{\"live_cursor\":{\"source_length\":5,\"source_sha256\":\"invalid\",\"active_indices\":[]}}"})
    void ignoresInvalidOptionalCursorWithoutBreakingRelay(final String response) throws Exception {
        server.enqueue(new MockResponse().setBody(response));
        server.enqueue(new MockResponse().setBody("{}"));
        try (var trigger = new LiveAnalyzeTrigger(client, 1, "", Duration.ofSeconds(2), meters)) {
            trigger.offer("cursor-contract", resultWith("First sentence."));
            assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNotNull();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(counter("audio_gw_live_analyze_publish_success_total")).isEqualTo(1));
            trigger.offer("cursor-contract", resultWith("Second sentence."));
            assertThat(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8())
                    .doesNotContain("previous_live_cursor");
        }
    }

    @Test
    void sentenceModeStillPublishesSpeechWithoutPunctuationByItsDeadline() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        try (var trigger = new LiveAnalyzeTrigger(client, 5, "", Duration.ofSeconds(2),
                meters, null, Duration.ZERO, Duration.ofMillis(100), true)) {
            trigger.offer("no-punctuation", resultWith("Mehmet raporu hazırlayacak"));
            assertThat(server.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8())
                    .contains("Mehmet raporu hazırlayacak");
        }
    }

    @Test
    void coalescesUpdatesWhileThePreviousAnalysisIsRunning() throws Exception {
        server.enqueue(new MockResponse().setBody("{}").setBodyDelay(1, TimeUnit.SECONDS));
        server.enqueue(new MockResponse().setBody("{}"));
        final LiveAnalyzeTrigger trigger =
                new LiveAnalyzeTrigger(client, 1, "", Duration.ofSeconds(3), meters);

        trigger.offer("synthetic-meeting", resultWith("First decision."));
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        trigger.offer("synthetic-meeting", resultWith("Second decision."));
        trigger.offer("synthetic-meeting", resultWith("Third decision."));

        assertThat(server.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        final RecordedRequest next = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(next).isNotNull();
        assertThat(next.getBody().readUtf8())
                .contains("First decision.", "Second decision.", "Third decision.");
        assertThat(server.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void cadenceRetainsNewestCumulativeContextUntilItCanStart() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        server.enqueue(new MockResponse().setBody("{}"));
        try (LiveAnalyzeTrigger trigger = new LiveAnalyzeTrigger(
                client, 1, "", Duration.ofSeconds(2), meters, null, Duration.ofMillis(500))) {
            trigger.offer("cadence", resultWith("Original decision."));
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                    assertThat(counter("audio_gw_live_analyze_publish_success_total")).isEqualTo(1));
            trigger.offer("cadence", resultWith("Second decision."));
            trigger.offer("cadence", resultWith("Newest decision."));
            assertThat(server.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
            final RecordedRequest next = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(next).isNotNull();
            assertThat(next.getBody().readUtf8())
                    .contains("Original decision.", "Second decision.", "Newest decision.", "\"segment_seq\":3");
            assertThat(counter("audio_gw_live_analyze_coalesced_total")).isEqualTo(1);
        }
    }

    @Test
    void timeoutReleasesSingleFlightAndPublishesLatestPendingContext() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        server.enqueue(new MockResponse().setBody("{}"));
        try (LiveAnalyzeTrigger trigger = new LiveAnalyzeTrigger(
                client, 1, "", Duration.ofMillis(400), meters)) {
            trigger.offer("timeout", resultWith("First decision."));
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            trigger.offer("timeout", resultWith("Latest decision."));
            assertThat(server.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
            final RecordedRequest next = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(next).isNotNull();
            assertThat(next.getBody().readUtf8()).contains("First decision.", "Latest decision.");
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                assertThat(counter("audio_gw_live_analyze_publish_error_total")).isEqualTo(1);
                assertThat(counter("audio_gw_live_analyze_publish_success_total")).isEqualTo(1);
            });
        }
    }

    @Test
    void aSlowMeetingDoesNotBlockOrContaminateAnotherMeeting() throws Exception {
        server.enqueue(new MockResponse().setBody("{}").setBodyDelay(1, TimeUnit.SECONDS));
        server.enqueue(new MockResponse().setBody("{}"));
        try (LiveAnalyzeTrigger trigger = new LiveAnalyzeTrigger(
                client, 1, "", Duration.ofSeconds(3), meters)) {
            trigger.offer("slow", resultWith("Slow meeting decision."));
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            trigger.offer("other", resultWith("Other meeting decision."));
            final RecordedRequest other = server.takeRequest(500, TimeUnit.MILLISECONDS);
            assertThat(other).isNotNull();
            assertThat(other.getBody().readUtf8())
                    .contains("Other meeting decision.").doesNotContain("Slow meeting decision.");
        }
    }

    @Test
    void closeCancelsDelayedWorkAndRejectsFurtherOffers() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        final LiveAnalyzeTrigger trigger = new LiveAnalyzeTrigger(
                client, 1, "", Duration.ofSeconds(2), meters, null, Duration.ofMillis(500));
        trigger.offer("closing", resultWith("First decision."));
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(counter("audio_gw_live_analyze_publish_success_total")).isEqualTo(1));
        trigger.offer("closing", resultWith("Pending decision."));
        trigger.close();
        trigger.offer("closing", resultWith("Ignored after close."));
        assertThat(server.takeRequest(700, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void flushesShortSpeechWithoutWaitingForAnotherFragmentOrRecordingStop() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        try (var trigger = new LiveAnalyzeTrigger(client, 5, "", Duration.ofSeconds(2),
                meters, null, Duration.ZERO, Duration.ofMillis(150))) {
            trigger.offer("short-speech", resultWith("The team approved the proposal."));
            final var request = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getBody().readUtf8()).contains("approved the proposal", "\"segment_seq\":1");
            assertThat(server.takeRequest(350, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void aCountFlushCancelsItsDeadlineButKeepsTheNextShortWindow() throws Exception {
        server.enqueue(new MockResponse().setBody("{}"));
        server.enqueue(new MockResponse().setBody("{}"));
        try (var trigger = new LiveAnalyzeTrigger(client, 2, "", Duration.ofSeconds(2),
                meters, null, Duration.ZERO, Duration.ofMillis(150))) {
            trigger.offer("mixed", resultWith("First decision."));
            trigger.offer("mixed", resultWith("Second decision."));
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            assertThat(server.takeRequest(350, TimeUnit.MILLISECONDS)).isNull();
            trigger.offer("mixed", resultWith("Final action before a pause."));
            final var next = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(next).isNotNull();
            assertThat(next.getBody().readUtf8()).contains("First decision.", "Final action", "\"segment_seq\":2");
            assertThat(server.takeRequest(350, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void timedWindowsStillCoalesceBehindAnInFlightAnalysis() throws Exception {
        server.enqueue(new MockResponse().setBody("{}").setBodyDelay(900, TimeUnit.MILLISECONDS));
        server.enqueue(new MockResponse().setBody("{}"));
        try (var trigger = new LiveAnalyzeTrigger(client, 2, "", Duration.ofSeconds(3),
                meters, null, Duration.ZERO, Duration.ofMillis(150))) {
            trigger.offer("slow-window", resultWith("First decision."));
            trigger.offer("slow-window", resultWith("First owner."));
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            trigger.offer("slow-window", resultWith("Second action."));
            assertThat(server.takeRequest(250, TimeUnit.MILLISECONDS)).isNull();
            trigger.offer("slow-window", resultWith("Newest action."));
            assertThat(server.takeRequest(250, TimeUnit.MILLISECONDS)).isNull();
            final var next = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(next).isNotNull();
            assertThat(next.getBody().readUtf8()).contains("First decision.", "Second action.", "Newest action.", "\"segment_seq\":3");
            assertThat(server.takeRequest(350, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    void closingCancelsAnUnderfilledWindow() throws Exception {
        final var trigger = new LiveAnalyzeTrigger(client, 5, "", Duration.ofSeconds(2),
                meters, null, Duration.ZERO, Duration.ofMillis(150));
        trigger.offer("closing-window", resultWith("Decision not yet flushed."));
        trigger.close();
        assertThat(server.takeRequest(350, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void liveCadenceConfigurationIsBoundedAndDefaultsToFifteenSeconds() {
        final var config = new com.example.audiogateway.config.AudioGatewayProperties.DirectStt.LiveAnalyze();
        assertThat(config.getMinIntervalMs()).isEqualTo(15_000);
        assertThat(config.getMaxWaitMs()).isEqualTo(15_000);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> config.setMaxWaitMs(0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> config.setMaxWaitMs(300_001))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> config.setMinIntervalMs(-1))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> config.setMinIntervalMs(300_001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void triggersOncePerSegmentWindowAndIncrementsSequence() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));

        final LiveAnalyzeTrigger trigger =
                new LiveAnalyzeTrigger(client, 2, "", Duration.ofSeconds(2), meters);

        trigger.offer("m-1", resultWith("Bütçe kararlaştırıldı."));
        // First result buffered; no request yet.
        assertThat(server.getRequestCount()).isZero();

        trigger.offer("m-1", resultWith("Ali hazırlayacak."));
        // Second result triggers the window.
        final RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(first.getPath()).isEqualTo("/analyze/live");
        final String body1 = first.getBody().readUtf8();
        assertThat(body1).contains("\"meeting_id\":\"m-1\"");
        assertThat(body1).contains("\"segment_seq\":1");
        assertThat(body1).contains("Bütçe kararlaştırıldı.");
        assertThat(body1).contains("Ali hazırlayacak.");

        trigger.offer("m-1", resultWith("Yeni segment."));
        trigger.offer("m-1", resultWith("Yeni segment devamı."));
        final RecordedRequest second = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(second).isNotNull();
        final String body2 = second.getBody().readUtf8();
        assertThat(body2).contains("\"segment_seq\":2");
        // Cumulative window (Zeynep 2026-08-31 finding 1): the second flush must
        // still carry the earlier fragments, not just the last window.
        assertThat(body2).contains("Bütçe kararlaştırıldı.");
        assertThat(body2).contains("Yeni segment devamı.");
    }

    @Test
    void cumulativeWindowDropsOldestTextBeyondTheCharBudget() {
        final LiveAnalyzeTrigger.Aggregation agg = new LiveAnalyzeTrigger.Aggregation(1);
        final String big = "x".repeat(LiveAnalyzeTrigger.Aggregation.MAX_CUMULATIVE_CHARS - 10);
        assertThat(agg.appendAndMaybeFlush(big).transcript()).hasSize(big.length());
        final LiveAnalyzeTrigger.Aggregation.Snapshot next = agg.appendAndMaybeFlush("tail fragment");
        assertThat(next.transcript()).endsWith("tail fragment");
        assertThat(next.transcript().length())
                .isLessThanOrEqualTo(LiveAnalyzeTrigger.Aggregation.MAX_CUMULATIVE_CHARS);
        assertThat(next.segmentSeq()).isEqualTo(2);
    }

    @Test
    void isolatesSequencesPerMeeting() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setResponseCode(200));

        final LiveAnalyzeTrigger trigger =
                new LiveAnalyzeTrigger(client, 1, "", Duration.ofSeconds(2), meters);

        trigger.offer("m-A", resultWith("A"));
        final RecordedRequest a = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(a).isNotNull();
        // Body can only be read once; take a single snapshot for both assertions.
        final String bodyA = a.getBody().readUtf8();
        assertThat(bodyA).contains("\"meeting_id\":\"m-A\"");
        assertThat(bodyA).contains("\"segment_seq\":1");

        trigger.offer("m-B", resultWith("B"));
        final RecordedRequest b = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(b).isNotNull();
        final String bodyB = b.getBody().readUtf8();
        // Meeting B starts its own sequence at 1, independent of meeting A.
        assertThat(bodyB).contains("\"meeting_id\":\"m-B\"");
        assertThat(bodyB).contains("\"segment_seq\":1");
    }

    @Test
    void addsBearerHeaderWhenTokenConfigured() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        final LiveAnalyzeTrigger trigger =
                new LiveAnalyzeTrigger(client, 1, "tok-123", Duration.ofSeconds(2), meters);

        trigger.offer("m-1", resultWith("hello"));
        final RecordedRequest req = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer tok-123");
    }

    @Test
    void dropsBlankTextAndMissingMeetingIdWithoutTriggering() {
        final LiveAnalyzeTrigger trigger =
                new LiveAnalyzeTrigger(client, 1, "", Duration.ofSeconds(2), meters);

        trigger.offer(null, resultWith("body"));
        trigger.offer("", resultWith("body"));
        trigger.offer("   ", resultWith("body"));
        trigger.offer("m-1", resultWith(""));
        trigger.offer("m-1", resultWith("   "));
        trigger.offer("m-1", null);

        assertThat(server.getRequestCount()).isZero();
        assertThat(counter("audio_gw_live_analyze_drop_total")).isEqualTo(6.0);
    }

    @Test
    void swallowsHttpErrorsAndNeverThrows() throws Exception {
        // 500 → error path; the offer call must still return cleanly.
        server.enqueue(new MockResponse().setResponseCode(500));
        final LiveAnalyzeTrigger trigger =
                new LiveAnalyzeTrigger(client, 1, "", Duration.ofSeconds(2), meters);

        assertThatCode(() -> trigger.offer("m-1", resultWith("payload")))
                .doesNotThrowAnyException();
        // Wait for the async fire to complete before asserting counters.
        server.takeRequest(2, TimeUnit.SECONDS);
        // Give the reactive pipeline a moment to record the error.
        Thread.sleep(200);
        assertThat(counter("audio_gw_live_analyze_publish_error_total"))
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void countsAttemptsAndSuccesses() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(202));
        final LiveAnalyzeTrigger trigger =
                new LiveAnalyzeTrigger(client, 1, "", Duration.ofSeconds(2), meters);

        trigger.offer("m-1", resultWith("payload"));
        server.takeRequest(2, TimeUnit.SECONDS);
        Thread.sleep(200);
        assertThat(counter("audio_gw_live_analyze_publish_total")).isEqualTo(1.0);
        assertThat(counter("audio_gw_live_analyze_publish_success_total")).isEqualTo(1.0);
    }
}
