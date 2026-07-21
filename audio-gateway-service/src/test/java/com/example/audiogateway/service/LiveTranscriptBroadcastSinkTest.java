package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.audiogateway.dto.TranscriptResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveTranscriptBroadcastSinkTest {

    private static TranscriptResult result(final String text) {
        return new TranscriptResult(text, "tr", 0.99, 1.0, 100.0, "m", "int8", "cpu", null);
    }

    private static DirectSttTranscriptResultContext context(final String meetingId) {
        return new DirectSttTranscriptResultContext(
                "session-x",
                1L,
                2L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0,
                "reason",
                meetingId,
                "dev-x",
                "tr",
                "pcm16",
                16000,
                1,
                "corr-x",
                "0".repeat(64),
                0);
    }

    @Test
    void forwardsToDelegateBeforeBroadcast() {
        final List<TranscriptResult> forwarded = new ArrayList<>();
        final DirectSttTranscriptResultSink base = (r, c) -> forwarded.add(r);
        final LiveTranscriptStreamHub hub = new LiveTranscriptStreamHub();
        final LiveTranscriptBroadcastSink sink = new LiveTranscriptBroadcastSink(base, hub);

        sink.emit(result("first"), context("m-1"));

        assertThat(forwarded).hasSize(1);
        assertThat(forwarded.get(0).text()).isEqualTo("first");
    }

    @Test
    void hubFailureDoesNotBreakDelegate() {
        final List<TranscriptResult> forwarded = new ArrayList<>();
        final DirectSttTranscriptResultSink base = (r, c) -> forwarded.add(r);
        final LiveTranscriptStreamHub throwingHub =
                new LiveTranscriptStreamHub() {
                    @Override
                    public void publish(final String meetingId, final TranscriptResult result) {
                        throw new RuntimeException("hub down");
                    }
                };
        final LiveTranscriptBroadcastSink sink =
                new LiveTranscriptBroadcastSink(base, throwingHub);

        assertThatCode(() -> sink.emit(result("second"), context("m-2")))
                .doesNotThrowAnyException();
        assertThat(forwarded).hasSize(1);
    }

    @Test
    void nullContextIsHandledGracefully() {
        final List<TranscriptResult> forwarded = new ArrayList<>();
        final DirectSttTranscriptResultSink base = (r, c) -> forwarded.add(r);
        final LiveTranscriptStreamHub hub = new LiveTranscriptStreamHub();
        final LiveTranscriptBroadcastSink sink = new LiveTranscriptBroadcastSink(base, hub);

        assertThatCode(() -> sink.emit(result("third"), null)).doesNotThrowAnyException();
        assertThat(forwarded).hasSize(1);
    }
}
