package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.audiogateway.service.ChunkReorderBuffer.Release;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reordering committed results before assembly (Faz 24 — transcript readability).
 *
 * <p>The forward to live-stt completes out of order, so folding in arrival order would
 * splice a sentence backwards. These tests pin three properties: the caller only ever
 * sees source order, nothing is silently dropped when a gap has to be abandoned, and an
 * arrival that comes back after its gap was abandoned is flagged rather than quietly
 * appended in the wrong place.
 */
class ChunkReorderBufferTest {

    private static final int CAPACITY = 4;
    private static final long WINDOW_MS = 3_000L;

    private final ChunkReorderBuffer<String> buffer = new ChunkReorderBuffer<>(CAPACITY, WINDOW_MS);

    private static String payload(final long seq) {
        return "e" + seq;
    }

    private static List<String> idsOf(final List<Release<String>> released) {
        return released.stream().map(Release::payload).toList();
    }

    private List<Release<String>> offer(final long seq, final long receiptAtMs) {
        return buffer.offer(seq, receiptAtMs, payload(seq));
    }

    @Test
    void in_order_arrivals_pass_straight_through() {
        assertThat(idsOf(offer(0, 0))).containsExactly("e0");
        assertThat(idsOf(offer(1, 100))).containsExactly("e1");
        assertThat(idsOf(offer(2, 200))).containsExactly("e2");
        assertThat(buffer.hasPending()).isFalse();
    }

    @Test
    void an_out_of_order_arrival_is_held_until_the_gap_fills() {
        assertThat(idsOf(offer(0, 0))).containsExactly("e0");

        // 2 arrives before 1 — releasing it now would reverse the sentence.
        assertThat(offer(2, 100)).isEmpty();
        assertThat(buffer.hasPending()).isTrue();

        final List<Release<String>> released = offer(1, 200);
        assertThat(idsOf(released)).containsExactly("e1", "e2");
        assertThat(released).allMatch(r -> !r.lateAfterGap());
        assertThat(buffer.hasPending()).isFalse();
    }

    @Test
    void a_burst_of_reversed_arrivals_is_released_in_source_order() {
        offer(0, 0);
        offer(3, 10);
        offer(2, 20);

        assertThat(idsOf(offer(1, 30))).containsExactly("e1", "e2", "e3");
    }

    @Test
    void the_very_first_arrival_of_a_session_may_itself_be_out_of_order() {
        // Window 1's forward completes before window 0's. Treating the first arrival as
        // the session start would emit the second half of the sentence first.
        assertThat(offer(1, 0)).isEmpty();

        assertThat(idsOf(offer(0, 50))).containsExactly("e0", "e1");
    }

    @Test
    void a_stalled_gap_is_released_on_the_reorder_window() {
        offer(0, 0);
        assertThat(offer(2, 1_000)).isEmpty();

        // Sequence 1 never arrives; before the window nothing moves...
        assertThat(buffer.sweep(2_000)).isEmpty();
        // ...and after it the held payload is released rather than stranded.
        assertThat(idsOf(buffer.sweep(4_500))).containsExactly("e2");
        assertThat(buffer.hasPending()).isFalse();
    }

    @Test
    void a_gap_is_abandoned_when_too_much_piles_up_behind_it() {
        offer(0, 0);
        final List<Release<String>> released = new ArrayList<>();
        // Sequence 1 is missing; 2..6 pile up behind it, exceeding capacity 4.
        for (long seq = 2; seq <= 6; seq++) {
            released.addAll(offer(seq, seq * 10));
        }

        assertThat(idsOf(released)).containsExactly("e2", "e3", "e4", "e5", "e6");
        assertThat(buffer.hasPending()).isFalse();
    }

    @Test
    void an_arrival_after_its_gap_was_abandoned_is_released_but_flagged_late() {
        offer(0, 0);
        offer(2, 100);
        buffer.sweep(5_000);

        // Sequence 1 finally lands. Losing it would drop speech; appending it to the
        // current line would silently produce "2 3 1". So it is released, flagged.
        final List<Release<String>> released = offer(1, 6_000);

        assertThat(idsOf(released)).containsExactly("e1");
        assertThat(released.get(0).lateAfterGap()).isTrue();
    }

    @Test
    void a_very_old_abandoned_sequence_is_still_released_after_many_gaps() {
        // Regression: the abandoned-set used to be bounded, so after enough gaps an old
        // abandoned sequence rolled out of memory and was silently reclassified as a
        // duplicate — losing speech while still claiming losslessness.
        offer(0, 0);
        // Abandon sequence 1, then churn far past any bounded window.
        offer(2, 10);
        buffer.sweep(5_000);
        for (long seq = 3; seq < 400; seq++) {
            offer(seq, 5_000 + seq);
        }

        final List<Release<String>> released = buffer.offer(1, 900_000, payload(1));

        assertThat(idsOf(released)).containsExactly("e1");
        assertThat(released.get(0).lateAfterGap()).isTrue();
        assertThat(released.get(0).unknownOld()).isFalse();
    }

    @Test
    void a_replayed_sequence_is_rejected_as_a_duplicate() {
        offer(0, 0);
        offer(1, 100);

        // A retry of an already-released sequence — releasing it again would duplicate
        // the sentence on screen.
        assertThat(buffer.offer(1, 200, payload(1))).isEmpty();
        assertThat(buffer.offer(0, 300, payload(0))).isEmpty();
    }

    @Test
    void a_late_arrival_is_released_only_once() {
        offer(0, 0);
        offer(2, 100);
        buffer.sweep(5_000);

        assertThat(offer(1, 6_000)).hasSize(1);
        // The retry of that same late sequence is a duplicate, not a second late line.
        assertThat(buffer.offer(1, 6_100, payload(1))).isEmpty();
    }

    @Test
    void a_session_that_does_not_start_at_zero_is_released_by_the_window() {
        // Sequences are session-scoped and start at 0, so a higher first sequence means
        // something is missing at the front. Wait for it, then move on rather than stall.
        assertThat(offer(500, 0)).isEmpty();

        assertThat(idsOf(buffer.sweep(3_500))).containsExactly("e500");
        assertThat(idsOf(offer(501, 3_600))).containsExactly("e501");
    }

    @Test
    void flush_all_releases_everything_in_source_order() {
        offer(0, 0);
        offer(3, 10);
        offer(2, 20);

        assertThat(idsOf(buffer.flushAll())).containsExactly("e2", "e3");
        assertThat(buffer.hasPending()).isFalse();
        assertThat(buffer.flushAll()).isEmpty();
    }

    @Test
    void sweep_on_an_empty_buffer_does_nothing() {
        assertThat(buffer.sweep(999_999)).isEmpty();
    }

    @Test
    void a_null_payload_is_ignored() {
        assertThat(buffer.offer(0, 0, null)).isEmpty();
    }

    @Test
    void bounds_that_could_never_release_a_gap_are_rejected() {
        assertThatThrownBy(() -> new ChunkReorderBuffer<String>(0, WINDOW_MS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChunkReorderBuffer<String>(CAPACITY, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
