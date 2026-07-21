package com.example.audiogateway.service;

import java.util.ArrayList;
import java.util.List;
import java.util.SequencedSet;
import java.util.TreeMap;

/**
 * Reorders committed STT fragments into source sequence before assembly (Faz 24 —
 * transcript readability).
 *
 * <p><b>Why.</b> The forward to live-stt completes out of order — {@code /transcribe} is
 * per-chunk stateless, so {@code DirectSttForwardingDispatcher} explicitly leaves
 * ordering to "the downstream transcript assembler … reconciles by {@code chunkSeq}".
 * Folding fragments in arrival order would splice a sentence out of order
 * ("konuşacağız Bugün toplantıda"). This buffer is that reconciliation step.
 *
 * <h2>Bounded, and still lossless</h2>
 * A reorder buffer cannot wait forever for a missing sequence. Two bounds release the
 * gap without dropping anything:
 * <ul>
 *   <li><b>capacity</b> — when too many out-of-order fragments pile up behind a hole,
 *       the hole is abandoned and the buffer resumes from the lowest held sequence;</li>
 *   <li><b>{@link #sweep} timeout</b> — when the oldest held fragment has waited past
 *       the reorder window, the same thing happens on the clock.</li>
 * </ul>
 * A fragment that finally arrives <i>after</i> its hole was abandoned is emitted
 * immediately and on its own rather than discarded — out of order, but never lost. A
 * sequence already emitted is dropped as a duplicate (a retry), which is the one case
 * where emitting again would corrupt the transcript.
 *
 * <h2>Threading</h2>
 * Not thread-safe — one instance per session, guarded by the owning sink's per-session
 * lock, exactly like {@link SentenceAssembler}.
 */
public final class ChunkReorderBuffer {

    private final int capacity;
    private final long reorderWindowMs;
    private final TreeMap<Long, SentenceAssembler.Fragment> pending = new TreeMap<>();

    /** Bounded remembered set of emitted sequences, newest last, for duplicate rejection. */
    private final SequencedSet<Long> emitted = new java.util.LinkedHashSet<>();

    /**
     * The next sequence expected at the front. Starts at 0 because
     * {@code DirectSttAudioWindowAggregator} numbers each session's windows from 0 — so
     * a session whose very first forward completes out of order (window 1 before window
     * 0) still waits for 0 instead of mistaking 1 for the start. A session that somehow
     * begins elsewhere is not stuck: {@link #sweep} releases the front on the reorder
     * window.
     */
    private long nextExpectedSeq;

    private long oldestPendingReceiptMs;

    /**
     * @param capacity how many out-of-order fragments may be held behind a gap before the
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

    /** How many emitted sequences to remember — enough to reject retries near the front. */
    private int emittedMemory() {
        return Math.max(64, capacity * 4);
    }

    /**
     * Offer a fragment at {@code seq}.
     *
     * @return the fragments now released, in source order — empty while waiting on a gap
     */
    public List<SentenceAssembler.Fragment> offer(
            final long seq, final SentenceAssembler.Fragment fragment) {
        if (fragment == null) {
            return List.of();
        }
        if (seq < nextExpectedSeq) {
            // Behind the front: either a duplicate of something already emitted, or a
            // fragment whose gap was abandoned. Drop the duplicate; emit the late arrival
            // on its own so it is never lost.
            if (emitted.contains(seq)) {
                return List.of();
            }
            return List.of(remember(seq, fragment));
        }

        pending.putIfAbsent(seq, fragment);
        if (pending.size() == 1) {
            oldestPendingReceiptMs = fragment.receiptAtMs();
        }
        if (pending.size() > capacity) {
            // Too much piled up behind the hole — abandon it and resume from the lowest
            // held sequence.
            nextExpectedSeq = pending.firstKey();
        }
        return drainReady();
    }

    /**
     * Release a stalled gap when the oldest held fragment has waited past the reorder
     * window.
     *
     * @param nowMs gateway receipt clock
     * @return the fragments released, in source order — empty when nothing timed out
     */
    public List<SentenceAssembler.Fragment> sweep(final long nowMs) {
        if (pending.isEmpty()) {
            return List.of();
        }
        if (nowMs - oldestPendingReceiptMs < reorderWindowMs) {
            return List.of();
        }
        nextExpectedSeq = pending.firstKey();
        return drainReady();
    }

    /**
     * Release everything held, in source order — the session is ending, so there is no
     * later fragment to wait for.
     */
    public List<SentenceAssembler.Fragment> flushAll() {
        if (pending.isEmpty()) {
            return List.of();
        }
        final List<SentenceAssembler.Fragment> out = new ArrayList<>(pending.size());
        pending.forEach((seq, frag) -> out.add(remember(seq, frag)));
        pending.clear();
        return out;
    }

    /** Whether any fragment is held waiting on a gap — test/observability helper. */
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    private List<SentenceAssembler.Fragment> drainReady() {
        final List<SentenceAssembler.Fragment> out = new ArrayList<>();
        while (!pending.isEmpty() && pending.firstKey() <= nextExpectedSeq) {
            final Long seq = pending.firstKey();
            final SentenceAssembler.Fragment frag = pending.remove(seq);
            out.add(remember(seq, frag));
            nextExpectedSeq = seq + 1;
        }
        if (!pending.isEmpty()) {
            oldestPendingReceiptMs = pending.firstEntry().getValue().receiptAtMs();
        }
        return out;
    }

    private SentenceAssembler.Fragment remember(
            final long seq, final SentenceAssembler.Fragment fragment) {
        emitted.add(seq);
        while (emitted.size() > emittedMemory()) {
            emitted.removeFirst();
        }
        if (seq >= nextExpectedSeq) {
            nextExpectedSeq = seq + 1;
        }
        return fragment;
    }
}
