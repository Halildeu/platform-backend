package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.audiogateway.dto.TranscriptResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class LiveAnalyzeTriggerSinkTest {

    private static TranscriptResult result(final String text) {
        return new TranscriptResult(text, "tr", 0.99, 1.0, 100.0, "m", "int8", "cpu", null);
    }

    @Test
    void forwardsToDelegateBeforeAggregating() throws Exception {
        final List<TranscriptResult> forwarded = new ArrayList<>();
        final DirectSttTranscriptResultSink base =
                (r, c) -> forwarded.add(r);

        final MockWebServer server = new MockWebServer();
        server.start();
        try {
            server.enqueue(new MockResponse().setResponseCode(200));
            final WebClient client = WebClient.builder()
                    .baseUrl(server.url("/").toString().replaceAll("/+$", ""))
                    .build();
            final LiveAnalyzeTrigger trigger =
                    new LiveAnalyzeTrigger(client, 1, "", Duration.ofSeconds(2), new SimpleMeterRegistry());
            final LiveAnalyzeTriggerSink sink = new LiveAnalyzeTriggerSink(base, trigger);

            final DirectSttTranscriptResultContext ctx = context("m-1");
            sink.emit(result("hello"), ctx);

            assertThat(forwarded).hasSize(1);
            assertThat(forwarded.get(0).text()).isEqualTo("hello");
        } finally {
            server.shutdown();
        }
    }

    @Test
    void aggregatorFailureDoesNotBreakDelegate() {
        final List<TranscriptResult> forwarded = new ArrayList<>();
        final DirectSttTranscriptResultSink base =
                (r, c) -> forwarded.add(r);

        // Use a trigger that would throw on any offer — segment window 0 would
        // fail the constructor, so instead we simulate by giving it a client
        // pointing at an unroutable address. offer() must still not throw.
        final WebClient bad = WebClient.builder().baseUrl("http://127.0.0.1:1").build();
        final LiveAnalyzeTrigger trigger =
                new LiveAnalyzeTrigger(bad, 1, "", Duration.ofMillis(200), new SimpleMeterRegistry());
        final LiveAnalyzeTriggerSink sink = new LiveAnalyzeTriggerSink(base, trigger);

        assertThatCode(() -> sink.emit(result("hello"), context("m-1")))
                .doesNotThrowAnyException();
        assertThat(forwarded).hasSize(1);
    }

    private static DirectSttTranscriptResultContext context(final String meetingId) {
        // Only meetingId matters here — every other field can be a plausible stub.
        return new DirectSttTranscriptResultContext(
                "session-x", // sessionId
                1L, // tenantId
                2L, // userId
                0L, // chunkSeq
                0L, // chunkStartedAtMs
                0L, // windowSeq
                0L, // firstChunkSeq
                0L, // lastChunkSeq
                0L, // windowStartedAtMs
                0L, // windowEndedAtMs
                0, // audioDurationMs
                "reason", // flushReason
                meetingId,
                "dev-x", // deviceId
                "tr", // requestedLanguage
                "pcm16", // audioFormat
                16000, // sampleRateHz
                1, // channels
                "corr-x", // correlationId
                "0".repeat(64), // sha256
                0, // byteLength
                DirectSttTranscriptResultContext.Transport.REST, 1L);
    }
}
