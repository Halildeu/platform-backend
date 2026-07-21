package com.example.audiogateway.service;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Reorders committed STT results into source sequence before assembly (Faz 24 —
 * transcript readability).
 *
 * <p><b>Why.</b> The forward to live-stt completes out of order — {@code /transcribe} is
 * per-chunk stateless, so {@code DirectSttForwardingDispatcher} explicitly leaves
 * ordering to "the downstream transcript assembler … reconciles by {@code chunkSeq}".
 * Folding in arrival order would splice a sentence backwards ("konuşacağız Bugün
 * toplantıda"). This buffer is that reconciliation step.
 *
 * <h2>Bounded, and honest about what the bound costs</h2>
 * A reorder buffer cannot wait forever for a missing sequence. Two bounds release the
 * gap: <b>capacity</b> (too many held behind a hole) and the <b>{@link #sweep} timeout</b>
 * (the hole stalled). Either way the hole is abandoned and the buffer resumes from the
 * lowest held sequence.
 *
 * <p>A payload that arrives <i>after</i> its hole was abandoned is still released — never
 * dropped — but it is flagged {@link Release#lateAfterGap()}. That distinction matters:
 * appending it to whatever sentence is open now would silently produce "2 3 1". Keeping
 * the text but misordering the meaning is not losslessness, so the caller is told to
 * handle it separately rather than folding it into the open buffer.
 *
 * <p>A sequence already released is dropped as a duplicate (a retry) — the one case where
 * releasing again would corrupt the transcript. Sequences whose gap was abandoned are
 * remembered explicitly, so a very late arrival is never mistaken for a duplicate.
 *
 * <p>That memory is EXACT, not bounded: a bounded set silently turns "abandoned, may still
 * arrive" into "already released, drop it" once it rolls over, which loses speech while
 * still claiming losslessness. Sequences are session-scoped and start at 0, so a
 * {@link BitSet} tracks every one for the price of one bit — a ten-hour meeting is under a
 * kilobyte. Past {@link #TRACKING_LIMIT} the buffer stops guessing and reports
 * {@link Release#unknownOld()} rather than dropping quietly.
 *
 * <h2>Threading</h2>
 * Not thread-safe — one instance per session, guarded by the owning sink's per-session
 * lock, exactly like {@link SentenceAssembler}.
 *
 * @param <T> the payload carried alongside the sequence number
 */
public final class ChunkReorderBuffer<T> {

    /**
     * One released payload.
     *
     * @param payload the caller's value
     * @param lateAfterGap true when this arrived after its gap was abandoned, so it is out
     *     of order relative to what the caller has already seen
     */
    public record Release<T>(T payload, boolean lateAfterGap, boolean unknownOld) {}

    private record Held<T>(T payload, long receiptAtMs) {}

    private final int capacity;
    private final long reorderWindowMs;
    private final TreeMap<Long, Held<T>> pending = new TreeMap<>();

    /** Exactly which sequences have been released — duplicate rejection. */
    private final BitSet released = new BitSet();

    /** Exactly which sequences were skipped when a gap was abandoned. */
    private final BitSet abandoned = new BitSet();

    /**
     * The next sequence expected at the front. Starts at 0 because
     * {@code DirectSttAudioWindowAggregator} numbers each session's windows from 0 — so a
     * session whose very first forward completes out of order (window 1 before window 0)
     * still waits for 0 instead of mistaking 1 for the start. A session that somehow
     * begins elsewhere is not stuck: {@link #sweep} releases the front on the window.
     */
    private long nextExpectedSeq;

    private long oldestPendingReceiptMs;

    /**
     * @param capacity how many out-of-order payloads may be held behind a gap before the
     *     gap is abandoned (must be > 0)
     * @param reorderWindowMs how long a gap may hold before {@link #sweep} abandons it
     */
    public ChunkReorderBuffer(final int capacity, final long reorderWindowMs) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        if (reorderWindowMs <= 0) {
            throw new IllegalArgumentException("reorderWindowMs must be > 0");
        }
        this.capacity = capacity;
        this.reorderWindowMs = reorderWindowMs;
    }

    /**
     * Upper bound on exact tracking. One window is ~5 s of audio, so this covers far more
     * than any real session; beyond it a stale arrival is reported as
     * {@link Release#unknownOld()} instead of being classified on a guess.
     */
    static final long TRACKING_LIMIT = 1_000_000L;

    /**
     * Offer a payload at {@code seq}.
     *
     * @param receiptAtMs gateway receipt clock, used only to time out a stalled gap
     * @return what is now released, in source order — empty while waiting on a gap
     */
    public List<Release<T>> offer(final long seq, final long receiptAtMs, final T payload) {
        if (payload == null) {
            return List.of();
        }

        if (seq < nextExpectedSeq) {
            if (!trackable(seq)) {
                // Outside exact tracking: it cannot be told apart from a retry, so it is
                // surfaced as unknown rather than dropped on a guess.
                return List.of(new Release<>(payload, true, true));
            }
            final int index = (int) seq;
            if (abandoned.get(index)) {
                // Its hole was given up on; releasing it in place would misorder the
                // transcript, so the caller is told it is late.
                abandoned.clear(index);
                released.set(index);
                return List.of(new Release<>(payload, true, false));
            }
            // Already released — a retry. Releasing it again would duplicate the line.
            return List.of();
        }

        pending.putIfAbsent(seq, new Held<>(payload, receiptAtMs));
        if (pending.size() == 1) {
            oldestPendingReceiptMs = receiptAtMs;
        }
        if (pending.size() > capacity) {
            abandonUpTo(pending.firstKey());
        }
        return drainReady();
    }

    /**
     * Release a stalled gap when the oldest held payload has waited past the reorder
     * window.
     *
     * @param nowMs gateway receipt clock
     */
    public List<Release<T>> sweep(final long nowMs) {
        if (pending.isEmpty() || nowMs - oldestPendingReceiptMs < reorderWindowMs) {
            return List.of();
        }
        abandonUpTo(pending.firstKey());
        return drainReady();
    }

    /**
     * Release everything held, in source order — the session is ending, so there is no
     * later payload to wait for.
     */
    public List<Release<T>> flushAll() {
        if (pending.isEmpty()) {
            return List.of();
        }
        final List<Release<T>> out = new ArrayList<>(pending.size());
        pending.forEach(
                (seq, held) -> {
                    remember(seq);
                    out.add(new Release<>(held.payload(), false, false));
                });
        pending.clear();
        return out;
    }

    /** Whether any payload is held waiting on a gap — test/observability helper. */
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    /** Skip the hole in front of {@code resumeAt}, recording what was given up on. */
    private void abandonUpTo(final long resumeAt) {
        for (long seq = nextExpectedSeq; seq < resumeAt; seq++) {
            if (trackable(seq)) {
                abandoned.set((int) seq);
            }
        }
        nextExpectedSeq = resumeAt;
    }

    private static boolean trackable(final long seq) {
        return seq >= 0 && seq < TRACKING_LIMIT;
    }

    private List<Release<T>> drainReady() {
        final List<Release<T>> out = new ArrayList<>();
        while (!pending.isEmpty() && pending.firstKey() <= nextExpectedSeq) {
            final Long seq = pending.firstKey();
            final Held<T> held = pending.remove(seq);
            remember(seq);
            nextExpectedSeq = seq + 1;
            out.add(new Release<>(held.payload(), false, false));
        }
        if (!pending.isEmpty()) {
            oldestPendingReceiptMs = pending.firstEntry().getValue().receiptAtMs();
        }
        return out;
    }

    private void remember(final long seq) {
        if (trackable(seq)) {
            released.set((int) seq);
            abandoned.clear((int) seq);
        }
        if (seq >= nextExpectedSeq) {
            nextExpectedSeq = seq + 1;
        }
    }
}
