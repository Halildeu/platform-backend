package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.audiogateway.service.SentenceAssembler.Fragment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Folding committed STT fragments into readable lines (Faz 24 — transcript readability).
 *
 * <p>The regression under test is the reported defect: live-stt commits on an acoustic
 * boundary, so one spoken sentence arrives as several fragments and rendered as several
 * lines. The assembler must produce one line — without ever losing, reordering or
 * rewriting a fragment, and without buffering forever when punctuation never comes.
 */
class SentenceAssemblerTest {

    private static final SentenceAssemblyPolicy POLICY =
            new SentenceAssemblyPolicy(12_000, 200, 2_000L);

    private final SentenceAssembler assembler = new SentenceAssembler(POLICY);

    /**
     * Source window [startMs, startMs+durationMs] with the gateway receipt clock at
     * startMs. The two clocks are deliberately separable: source timestamps drive
     * speech-gap detection, the receipt clock drives the trailing-line bound.
     */
    private static Fragment fragment(
            final String id, final String text, final long startMs, final int durationMs) {
        return new Fragment(id, text, startMs, startMs + durationMs, durationMs, startMs);
    }

    @Test
    void one_sentence_split_across_pauses_becomes_one_line() {
        // Exactly the reported case: a speaker pausing mid-sentence past live-stt's
        // 0.7 s silence commit, so the sentence lands as three committed fragments.
        assertThat(assembler.offer(fragment("e1", "Bugün toplantıda", 0, 1_500))).isEmpty();
        assertThat(assembler.offer(fragment("e2", "bütçe kalemlerini", 2_000, 1_500))).isEmpty();

        final List<AssembledUtterance> closed =
                assembler.offer(fragment("e3", "konuşacağız.", 4_000, 1_000));

        assertThat(closed).hasSize(1);
        final AssembledUtterance line = closed.get(0);
        assertThat(line.text()).isEqualTo("Bugün toplantıda bütçe kalemlerini konuşacağız.");
        assertThat(line.sourceEventIds()).containsExactly("e1", "e2", "e3");
        assertThat(line.flushReason()).isEqualTo(SentenceAssembler.REASON_PUNCTUATION);
        assertThat(line.startedAtMs()).isZero();
        assertThat(line.endedAtMs()).isEqualTo(5_000);
        assertThat(line.speechDurationMs()).isEqualTo(4_000);
    }

    @Test
    void every_fragment_appears_exactly_once_and_in_order() {
        final List<String> offered = new ArrayList<>();
        final List<AssembledUtterance> lines = new ArrayList<>();
        long at = 0;
        for (int i = 1; i <= 12; i++) {
            // No terminators at all — the text must still come out whole, via the bounds.
            final String word = "parça" + i;
            offered.add(word);
            lines.addAll(assembler.offer(fragment("e" + i, word, at, 900)));
            at += 1_000;
        }
        assembler.closeSession().ifPresent(lines::add);

        final String reassembled = String.join(" ", lines.stream().map(AssembledUtterance::text).toList());
        assertThat(reassembled).isEqualTo(String.join(" ", offered));

        final List<String> sourceIds =
                lines.stream().flatMap(l -> l.sourceEventIds().stream()).toList();
        assertThat(sourceIds).hasSize(12).doesNotHaveDuplicates();
    }

    @Test
    void a_pause_closes_the_previous_line_before_the_next_fragment_opens_one() {
        assembler.offer(fragment("e1", "ilk düşünce", 0, 1_000));

        // 3 s of no committed speech — past the 2 s idle bound.
        final List<AssembledUtterance> closed =
                assembler.offer(fragment("e2", "yeni düşünce", 4_000, 1_000));

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).text()).isEqualTo("ilk düşünce");
        assertThat(closed.get(0).flushReason()).isEqualTo(SentenceAssembler.REASON_IDLE);
        assertThat(closed.get(0).sourceEventIds()).containsExactly("e1");
        // The new fragment opened the next line rather than joining the closed one.
        assertThat(assembler.hasBufferedText()).isTrue();
    }

    @Test
    void a_long_fragment_is_not_mistaken_for_a_pause() {
        // A speech gap is measured between the END of one window and the START of the
        // next. A fragment holding 5 s of speech takes 5 s to arrive without the speaker
        // pausing at all — measuring elapsed time instead would split this sentence.
        assembler.offer(fragment("e1", "uzun bir cümlenin ilk yarısı", 0, 5_000));

        final List<AssembledUtterance> closed =
                assembler.offer(fragment("e2", "ve ikinci yarısı.", 5_100, 1_000));

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).sourceEventIds()).containsExactly("e1", "e2");
        assertThat(closed.get(0).flushReason()).isEqualTo(SentenceAssembler.REASON_PUNCTUATION);
    }

    @Test
    void unpunctuated_speech_still_closes_on_the_duration_bound() {
        long at = 0;
        List<AssembledUtterance> closed = List.of();
        for (int i = 1; i <= 5 && closed.isEmpty(); i++) {
            closed = assembler.offer(fragment("e" + i, "aralıksız konuşma", at, 3_000));
            at += 3_000;
        }
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).flushReason()).isEqualTo(SentenceAssembler.REASON_MAX_DURATION);
        assertThat(closed.get(0).speechDurationMs()).isGreaterThanOrEqualTo(POLICY.maxSpeechMs());
    }

    @Test
    void unpunctuated_speech_still_closes_on_the_length_bound() {
        final SentenceAssembler shortLines =
                new SentenceAssembler(new SentenceAssemblyPolicy(60_000, 40, 2_000L));
        shortLines.offer(fragment("e1", "yirmi karakterlik metin", 0, 500));

        final List<AssembledUtterance> closed =
                shortLines.offer(fragment("e2", "ve devamı gelen kısım", 600, 500));

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).flushReason()).isEqualTo(SentenceAssembler.REASON_MAX_LENGTH);
        assertThat(closed.get(0).text()).isEqualTo("yirmi karakterlik metin ve devamı gelen kısım");
    }

    @Test
    void punctuation_wins_over_the_bounds() {
        final SentenceAssembler tight =
                new SentenceAssembler(new SentenceAssemblyPolicy(1_000, 10, 2_000L));

        final List<AssembledUtterance> closed = tight.offer(fragment("e1", "Tamam.", 0, 5_000));

        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).flushReason()).isEqualTo(SentenceAssembler.REASON_PUNCTUATION);
    }

    @Test
    void session_end_flushes_whatever_is_buffered() {
        assembler.offer(fragment("e1", "yarım kalmış bir cümle", 0, 1_000));

        final AssembledUtterance line = assembler.closeSession().orElseThrow();

        assertThat(line.text()).isEqualTo("yarım kalmış bir cümle");
        assertThat(line.flushReason()).isEqualTo(SentenceAssembler.REASON_SESSION_END);
        assertThat(assembler.hasBufferedText()).isFalse();
        // Idempotent: nothing is emitted twice on a repeated close.
        assertThat(assembler.closeSession()).isEmpty();
    }

    @Test
    void blank_fragments_are_ignored_entirely() {
        assertThat(assembler.offer(fragment("e1", "   ", 0, 500))).isEmpty();
        assertThat(assembler.offer(fragment("e2", null, 600, 500))).isEmpty();
        assertThat(assembler.hasBufferedText()).isFalse();

        final List<AssembledUtterance> closed =
                assembler.offer(fragment("e3", "Gerçek metin.", 1_200, 500));

        // A blank fragment must not appear in a line's source list — the list names
        // fragments whose text is actually in the line.
        assertThat(closed.get(0).sourceEventIds()).containsExactly("e3");
    }

    @Test
    void a_null_fragment_is_ignored() {
        assertThat(assembler.offer(null)).isEmpty();
    }

    @Test
    void periodic_idle_flush_closes_a_trailing_line_without_a_new_fragment() {
        assembler.offer(fragment("e1", "son söz", 0, 1_000));

        assertThat(assembler.flushIfIdle(1_999)).isEmpty();
        assertThat(assembler.flushIfIdle(2_000)).isPresent();
        assertThat(assembler.hasBufferedText()).isFalse();
    }

    @Test
    void idle_flush_on_an_empty_buffer_emits_nothing() {
        assertThat(assembler.flushIfIdle(999_999)).isEmpty();
    }

    @Test
    void fragments_without_a_duration_fall_back_to_the_window_length() {
        final SentenceAssembler a = new SentenceAssembler(POLICY);
        a.offer(new Fragment("e1", "süresiz parça", 1_000, 4_000, 0, 1_000));

        final AssembledUtterance line = a.closeSession().orElseThrow();

        assertThat(line.speechDurationMs()).isEqualTo(3_000);
    }

    @Test
    void policy_rejects_bounds_that_would_never_close_a_line() {
        assertThatThrownBy(() -> new SentenceAssemblyPolicy(0, 200, 2_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentenceAssemblyPolicy(12_000, 0, 2_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentenceAssemblyPolicy(12_000, 200, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assembled_lines_are_immutable_snapshots() {
        final List<String> ids = new ArrayList<>(List.of("e1"));
        final AssembledUtterance line =
                new AssembledUtterance("metin", ids, 0, 1, 1, SentenceAssembler.REASON_IDLE);
        ids.add("e2");

        assertThat(line.sourceEventIds()).containsExactly("e1");
        assertThat(line.textLength()).isEqualTo(5);
    }
}
