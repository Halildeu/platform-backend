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

    /**
     * A web viewer has to tell a raw acoustic chunk apart from the assembled line, or it
     * renders the same sentence twice — once whole, once in pieces. The broadcast must
     * therefore carry the assembly provenance, not just the text.
     */
    @Test
    void broadcastCarriesAssemblyProvenance() {
        final List<com.example.audiogateway.dto.LiveTranscriptEvent> published = new ArrayList<>();
        final LiveTranscriptStreamHub hub =
                new LiveTranscriptStreamHub() {
                    @Override
                    public void publish(
                            final String meetingId,
                            final com.example.audiogateway.dto.LiveTranscriptEvent event) {
                        published.add(event);
                    }
                };
        final LiveTranscriptBroadcastSink sink =
                new LiveTranscriptBroadcastSink((r, c) -> {}, hub);

        sink.emit(result("ham parça"), context("m-3"));
        sink.emit(
                result("Ham parça birleşmiş hâli."),
                context("m-3")
                        .withAssembly(
                                new AssembledUtterance(
                                        "Ham parça birleşmiş hâli.",
                                        List.of("0:0", "1:1"),
                                        0,
                                        3_000,
                                        3_000,
                                        SentenceAssembler.REASON_PUNCTUATION)));

        assertThat(published).hasSize(2);
        assertThat(published.get(0).status())
                .isEqualTo(com.example.audiogateway.dto.LiveTranscriptEvent.STATUS_DRAFT);
        assertThat(published.get(0).assemblyReason()).isNull();
        assertThat(published.get(0).sourceEventIds()).isEmpty();

        assertThat(published.get(1).status())
                .isEqualTo(com.example.audiogateway.dto.LiveTranscriptEvent.STATUS_UTTERANCE);
        assertThat(published.get(1).assemblyReason())
                .isEqualTo(SentenceAssembler.REASON_PUNCTUATION);
        assertThat(published.get(1).sourceEventIds()).containsExactly("0:0", "1:1");
        assertThat(published.get(1).text()).isEqualTo("Ham parça birleşmiş hâli.");
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
                    public void publish(
                            final String meetingId,
                            final com.example.audiogateway.dto.LiveTranscriptEvent event) {
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
