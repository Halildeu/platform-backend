package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.audiogateway.dto.TranscriptResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The sink seam of sentence assembly (Faz 24 — transcript readability).
 *
 * <p>What must hold no matter what the assembler decides: the raw chunk still reaches
 * the durable delegate first and unchanged, the assembled line is an addition rather
 * than a replacement, and a failure while emitting the convenience line never turns an
 * already-delivered transcript into a failed one.
 */
class SentenceAssemblingSinkTest {

    private static final String SESSION = "sess-1";

    private final List<Emission> emissions = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(0);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private record Emission(TranscriptResult result, DirectSttTranscriptResultContext context) {}

    private final DirectSttTranscriptResultSink recorder =
            (result, context) -> emissions.add(new Emission(result, context));

    private SentenceAssemblingSink sink(final DirectSttTranscriptResultSink delegate) {
        return new SentenceAssemblingSink(
                delegate, new SentenceAssemblyPolicy(12_000, 200, 2_000L), meters, now::get);
    }

    private static TranscriptResult result(final String text) {
        return new TranscriptResult(text, "tr", 0.98d, 2.0d, 120.0d, "medium", "float16", "cuda", null);
    }

    private static DirectSttTranscriptResultContext context(
            final long windowSeq, final long startedAtMs, final int durationMs) {
        return new DirectSttTranscriptResultContext(
                SESSION, 1L, 2L, windowSeq, startedAtMs, windowSeq, windowSeq, windowSeq,
                startedAtMs, startedAtMs + durationMs, durationMs, "chunk", "meet-1", "dev-1",
                "tr", "wav", 16_000, 1, "corr-1", "sha", 100);
    }

    private void offer(
            final SentenceAssemblingSink sink,
            final String text,
            final long windowSeq,
            final long startedAtMs,
            final int durationMs) {
        now.set(startedAtMs);
        sink.emit(result(text), context(windowSeq, startedAtMs, durationMs));
    }

    /** Emit with the receipt clock decoupled from the source window. */
    private void offerAt(
            final SentenceAssemblingSink sink,
            final String text,
            final long windowSeq,
            final long startedAtMs,
            final int durationMs,
            final long receiptAtMs) {
        now.set(receiptAtMs);
        sink.emit(result(text), context(windowSeq, startedAtMs, durationMs));
    }

    @Test
    void raw_chunks_are_forwarded_first_and_unchanged() {
        final SentenceAssemblingSink sink = sink(recorder);

        offer(sink, "Bugün toplantıda", 0, 0, 1_500);

        assertThat(emissions).hasSize(1);
        assertThat(emissions.get(0).result().text()).isEqualTo("Bugün toplantıda");
        assertThat(emissions.get(0).context().assembly()).isNull();
    }

    @Test
    void a_completed_sentence_is_emitted_as_an_additional_assembled_line() {
        final SentenceAssemblingSink sink = sink(recorder);

        offer(sink, "Bugün toplantıda", 0, 0, 1_500);
        offer(sink, "bütçeyi konuşacağız.", 1, 1_600, 1_500);

        // Two raw chunks, then the assembled line — nothing replaced.
        assertThat(emissions).hasSize(3);
        assertThat(emissions.get(0).context().assembly()).isNull();
        assertThat(emissions.get(1).context().assembly()).isNull();

        final Emission assembled = emissions.get(2);
        assertThat(assembled.result().text()).isEqualTo("Bugün toplantıda bütçeyi konuşacağız.");
        assertThat(assembled.context().assembly()).isNotNull();
        assertThat(assembled.context().assembly().reason())
                .isEqualTo(SentenceAssembler.REASON_PUNCTUATION);
        assertThat(assembled.context().assembly().sourceEventIds()).containsExactly("0:0", "1:1");
        assertThat(assembled.context().windowStartedAtMs()).isZero();
        assertThat(assembled.context().windowEndedAtMs()).isEqualTo(3_100);
    }

    @Test
    void the_assembled_line_carries_model_metadata_but_no_invented_elapsed_time() {
        final SentenceAssemblingSink sink = sink(recorder);

        offer(sink, "Tamam.", 0, 0, 1_000);

        final TranscriptResult assembled = emissions.get(1).result();
        assertThat(assembled.language()).isEqualTo("tr");
        assertThat(assembled.model()).isEqualTo("medium");
        assertThat(assembled.device()).isEqualTo("cuda");
        // No inference produced this line, so claiming an elapsed time would be a lie.
        assertThat(assembled.elapsedMs()).isNull();
        assertThat(assembled.durationSeconds()).isEqualTo(1.0d);
    }

    @Test
    void assembled_lines_are_never_re_assembled() {
        // Feeding the sink its own output would fold every line into the next one.
        final SentenceAssemblingSink sink = sink(recorder);

        offer(sink, "Tamam.", 0, 0, 1_000);
        final DirectSttTranscriptResultContext assembledContext = emissions.get(1).context();
        emissions.clear();

        sink.emit(result("Tamam."), assembledContext);

        assertThat(emissions).hasSize(1);
        assertThat(emissions.get(0).context().assembly()).isNotNull();
    }

    @Test
    void a_failing_assembled_emission_does_not_fail_the_session() {
        final List<Emission> delivered = new ArrayList<>();
        final SentenceAssemblingSink sink =
                sink(
                        (result, context) -> {
                            if (context != null && context.assembly() != null) {
                                throw new IllegalStateException("stream write refused");
                            }
                            delivered.add(new Emission(result, context));
                        });

        assertThatCode(() -> offer(sink, "Tamam.", 0, 0, 1_000)).doesNotThrowAnyException();
        assertThat(delivered).hasSize(1);
    }

    @Test
    void a_failing_raw_emission_still_propagates() {
        // The durable handoff is the contract; swallowing its failure would report an
        // unpersisted transcript as delivered.
        final SentenceAssemblingSink sink =
                sink(
                        (result, context) -> {
                            throw new IllegalStateException("durable write refused");
                        });

        assertThatCode(() -> offer(sink, "Tamam.", 0, 0, 1_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void session_close_flushes_a_trailing_line() {
        final SentenceAssemblingSink sink = sink(recorder);
        offer(sink, "yarım kalmış cümle", 0, 0, 1_000);
        emissions.clear();

        sink.closeSession(SESSION);

        assertThat(emissions).hasSize(1);
        assertThat(emissions.get(0).result().text()).isEqualTo("yarım kalmış cümle");
        assertThat(emissions.get(0).context().assembly().reason())
                .isEqualTo(SentenceAssembler.REASON_SESSION_END);
        // Idempotent — a second close emits nothing.
        sink.closeSession(SESSION);
        assertThat(emissions).hasSize(1);
    }

    @Test
    void the_sweep_closes_a_line_whose_speaker_stopped() {
        // Without the sweep this line would wait for a fragment that never comes.
        final SentenceAssemblingSink sink = sink(recorder);
        offer(sink, "son söz", 0, 0, 1_000);
        emissions.clear();

        now.set(1_500);
        sink.sweep();
        assertThat(emissions).isEmpty();

        now.set(4_000);
        sink.sweep();

        assertThat(emissions).hasSize(1);
        assertThat(emissions.get(0).context().assembly().reason())
                .isEqualTo(SentenceAssembler.REASON_IDLE);
    }

    @Test
    void the_sweep_evicts_long_idle_sessions() {
        final SentenceAssemblingSink sink = sink(recorder);
        offer(sink, "Tamam.", 0, 0, 1_000);
        assertThat(sink.trackedSessions()).isEqualTo(1);

        now.set(1_000 + SentenceAssemblingSink.SESSION_EVICT_AFTER_MS);
        sink.sweep();

        assertThat(sink.trackedSessions()).isZero();
    }

    @Test
    void results_without_a_session_are_forwarded_but_not_assembled() {
        final SentenceAssemblingSink sink = sink(recorder);
        final DirectSttTranscriptResultContext noSession =
                new DirectSttTranscriptResultContext(
                        "  ", 1L, 2L, 0, 0, 0, 0, 0, 0, 1_000, 1_000, "chunk", "meet-1", "dev-1",
                        "tr", "wav", 16_000, 1, "corr-1", "sha", 100);

        sink.emit(result("Tamam."), noSession);

        assertThat(emissions).hasSize(1);
        assertThat(sink.trackedSessions()).isZero();
    }

    @Test
    void assembled_lines_are_counted_by_reason() {
        final SentenceAssemblingSink sink = sink(recorder);

        offer(sink, "Tamam.", 0, 0, 1_000);

        assertThat(
                        meters.counter(
                                        "audio_gateway_direct_stt_assembled_lines_total",
                                        "reason",
                                        SentenceAssembler.REASON_PUNCTUATION)
                                .count())
                .isEqualTo(1.0d);
    }

    @Test
    void out_of_order_forwards_are_folded_in_source_order() {
        // The forward to live-stt completes out of order; folding by arrival would
        // splice the sentence backwards ("konuşacağız. Bugün toplantıda").
        final SentenceAssemblingSink sink = sink(recorder);

        offer(sink, "bütçeyi konuşacağız.", 1, 1_600, 1_500);
        offer(sink, "Bugün toplantıda", 0, 0, 1_500);

        final List<Emission> assembled =
                emissions.stream().filter(e -> e.context().assembly() != null).toList();
        assertThat(assembled).hasSize(1);
        assertThat(assembled.get(0).result().text())
                .isEqualTo("Bugün toplantıda bütçeyi konuşacağız.");
        assertThat(assembled.get(0).context().assembly().sourceEventIds())
                .containsExactly("0:0", "1:1");
    }

    @Test
    void a_stalled_reorder_gap_is_released_by_the_sweep() {
        final SentenceAssemblingSink sink = sink(recorder);
        offer(sink, "ilk parça", 0, 0, 1_000);
        // Window 1 never arrives; window 2 waits behind the hole.
        offer(sink, "geç gelen parça.", 2, 2_000, 1_000);
        emissions.clear();

        now.set(2_000 + SentenceAssemblingSink.REORDER_WINDOW_MS);
        sink.sweep();

        assertThat(emissions).hasSize(1);
        assertThat(emissions.get(0).result().text()).isEqualTo("ilk parça geç gelen parça.");
    }

    @Test
    void a_fresh_line_is_not_flushed_by_a_stale_source_timestamp() {
        // The contract allows a source window timestamp to be 0 or an arbitrary past
        // value. Comparing one against wall-clock time would close a line that just
        // opened, on the very first sweep.
        final SentenceAssemblingSink sink = sink(recorder);
        offerAt(sink, "yeni açılmış satır", 0, 0, 1_000, 1_000_000L);
        emissions.clear();

        now.set(1_000_500L);
        sink.sweep();

        assertThat(emissions).isEmpty();

        now.set(1_003_000L);
        sink.sweep();
        assertThat(emissions).hasSize(1);
        assertThat(emissions.get(0).context().assembly().reason())
                .isEqualTo(SentenceAssembler.REASON_IDLE);
    }

    @Test
    void a_failed_assembled_emission_is_metered_not_silent() {
        final SentenceAssemblingSink sink =
                sink(
                        (result, context) -> {
                            if (context != null && context.assembly() != null) {
                                throw new IllegalStateException("stream write refused");
                            }
                        });

        offer(sink, "Tamam.", 0, 0, 1_000);

        assertThat(
                        meters.counter(
                                        "audio_gateway_direct_stt_assembled_line_failures_total",
                                        "reason",
                                        SentenceAssembler.REASON_PUNCTUATION)
                                .count())
                .isEqualTo(1.0d);
    }

    @Test
    void closing_an_unknown_session_is_a_no_op() {
        final SentenceAssemblingSink sink = sink(recorder);

        assertThatCode(() -> sink.closeSession("nope")).doesNotThrowAnyException();
        assertThatCode(() -> sink.closeSession(null)).doesNotThrowAnyException();
        assertThat(emissions).isEmpty();
    }
}
