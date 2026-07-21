package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.audiogateway.service.SentenceAssembler.Fragment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reordering committed fragments before assembly (Faz 24 — transcript readability).
 *
 * <p>The forward to live-stt completes out of order, so folding in arrival order would
 * splice a sentence backwards. These tests pin the two properties that matter: the
 * assembler only ever sees source order, and no fragment is silently dropped when a gap
 * has to be abandoned.
 */
class ChunkReorderBufferTest {

    private static final int CAPACITY = 4;
    private static final long WINDOW_MS = 3_000L;

    private final ChunkReorderBuffer buffer = new ChunkReorderBuffer(CAPACITY, WINDOW_MS);

    private static Fragment fragment(final long seq, final long receiptAtMs) {
        return new Fragment("e" + seq, "parça" + seq, seq * 1_000, seq * 1_000 + 900, 900, receiptAtMs);
    }

    private static List<String> idsOf(final List<Fragment> fragments) {
        return fragments.stream().map(Fragment::eventId).toList();
    }

    @Test
    void in_order_fragments_pass_straight_through() {
        assertThat(idsOf(buffer.offer(0, fragment(0, 0)))).containsExactly("e0");
        assertThat(idsOf(buffer.offer(1, fragment(1, 100)))).containsExactly("e1");
        assertThat(idsOf(buffer.offer(2, fragment(2, 200)))).containsExactly("e2");
        assertThat(buffer.hasPending()).isFalse();
    }

    @Test
    void an_out_of_order_arrival_is_held_until_the_gap_fills() {
        assertThat(idsOf(buffer.offer(0, fragment(0, 0)))).containsExactly("e0");

        // 2 arrives before 1 — releasing it now would reverse the sentence.
        assertThat(buffer.offer(2, fragment(2, 100))).isEmpty();
        assertThat(buffer.hasPending()).isTrue();

        assertThat(idsOf(buffer.offer(1, fragment(1, 200)))).containsExactly("e1", "e2");
        assertThat(buffer.hasPending()).isFalse();
    }

    @Test
    void a_burst_of_reversed_arrivals_is_released_in_source_order() {
        buffer.offer(0, fragment(0, 0));
        buffer.offer(3, fragment(3, 10));
        buffer.offer(2, fragment(2, 20));

        assertThat(idsOf(buffer.offer(1, fragment(1, 30)))).containsExactly("e1", "e2", "e3");
    }

    @Test
    void a_stalled_gap_is_released_on_the_reorder_window() {
        buffer.offer(0, fragment(0, 0));
        assertThat(buffer.offer(2, fragment(2, 1_000))).isEmpty();

        // Sequence 1 never arrives; before the window nothing moves...
        assertThat(buffer.sweep(2_000)).isEmpty();
        // ...and after it the held fragment is released rather than stranded.
        assertThat(idsOf(buffer.sweep(4_500))).containsExactly("e2");
        assertThat(buffer.hasPending()).isFalse();
    }

    @Test
    void a_gap_is_abandoned_when_too_much_piles_up_behind_it() {
        buffer.offer(0, fragment(0, 0));
        final List<Fragment> released = new ArrayList<>();
        // Sequence 1 is missing; 2..6 pile up behind it, exceeding capacity 4.
        for (long seq = 2; seq <= 6; seq++) {
            released.addAll(buffer.offer(seq, fragment(seq, seq * 10)));
        }

        assertThat(idsOf(released)).containsExactly("e2", "e3", "e4", "e5", "e6");
        assertThat(buffer.hasPending()).isFalse();
    }

    @Test
    void a_fragment_arriving_after_its_gap_was_abandoned_is_still_emitted() {
        buffer.offer(0, fragment(0, 0));
        buffer.offer(2, fragment(2, 100));
        buffer.sweep(5_000);

        // Sequence 1 finally lands. Out of order now, but losing it would drop speech.
        assertThat(idsOf(buffer.offer(1, fragment(1, 6_000)))).containsExactly("e1");
    }

    @Test
    void a_replayed_sequence_is_rejected_as_a_duplicate() {
        buffer.offer(0, fragment(0, 0));
        buffer.offer(1, fragment(1, 100));

        // A retry of an already-emitted sequence — emitting it again would duplicate
        // the sentence on screen.
        assertThat(buffer.offer(1, fragment(1, 200))).isEmpty();
        assertThat(buffer.offer(0, fragment(0, 300))).isEmpty();
    }

    @Test
    void the_very_first_fragment_of_a_session_may_itself_be_out_of_order() {
        // Window 1's forward completes before window 0's. Treating the first arrival as
        // the session start would emit the second half of the sentence first.
        assertThat(buffer.offer(1, fragment(1, 0))).isEmpty();

        assertThat(idsOf(buffer.offer(0, fragment(0, 50)))).containsExactly("e0", "e1");
    }

    @Test
    void a_session_that_does_not_start_at_zero_is_released_by_the_window() {
        // Sequences are session-scoped and start at 0, so a higher first sequence means
        // something is missing at the front. Wait for it, then move on rather than stall.
        assertThat(buffer.offer(500, fragment(500, 0))).isEmpty();

        assertThat(idsOf(buffer.sweep(3_500))).containsExactly("e500");
        assertThat(idsOf(buffer.offer(501, fragment(501, 3_600)))).containsExactly("e501");
    }

    @Test
    void flush_all_releases_everything_in_source_order() {
        buffer.offer(0, fragment(0, 0));
        buffer.offer(3, fragment(3, 10));
        buffer.offer(2, fragment(2, 20));

        assertThat(idsOf(buffer.flushAll())).containsExactly("e2", "e3");
        assertThat(buffer.hasPending()).isFalse();
        assertThat(buffer.flushAll()).isEmpty();
    }

    @Test
    void sweep_on_an_empty_buffer_does_nothing() {
        assertThat(buffer.sweep(999_999)).isEmpty();
    }

    @Test
    void a_null_fragment_is_ignored() {
        assertThat(buffer.offer(0, null)).isEmpty();
    }

    @Test
    void bounds_that_could_never_release_a_gap_are_rejected() {
        assertThatThrownBy(() -> new ChunkReorderBuffer(0, WINDOW_MS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkReorderBuffer(CAPACITY, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
