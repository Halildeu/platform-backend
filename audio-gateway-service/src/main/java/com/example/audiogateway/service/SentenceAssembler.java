package com.example.audiogateway.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Folds consecutive committed STT fragments into sentence-level transcript lines
 * (Faz 24 — transcript readability).
 *
 * <p><b>The defect.</b> live-stt commits on an acoustic boundary — a forced commit every
 * few seconds, or 0.7 s of silence — so a speaker who pauses mid-sentence has the
 * sentence cut, and each cut renders as its own line. One sentence arrives as five to
 * ten lines. The REST fallback is worse: 2 s chunks are transcribed independently with
 * no merge at all.
 *
 * <p><b>The fix.</b> Buffer committed fragments and close a line when the text reaches a
 * linguistic boundary ({@link SentenceBoundary}) instead of an acoustic one. This is a
 * pure text-domain transform: no model, no GPU, no rewriting.
 *
 * <h2>Losslessness (whitespace-normalised)</h2>
 * Every fragment offered is emitted in exactly one line, in source order. The only edits
 * are whitespace ones: leading and trailing whitespace is stripped and fragments are
 * joined with a single space. No word is dropped, reordered, corrected or invented, no
 * repeat is deleted, no punctuation is added, and no LLM is involved — so the assembled
 * line remains usable as the verbatim record.
 *
 * <h2>Bounded buffering</h2>
 * A line always closes, even when the speaker never produces a terminator: on speech
 * duration, on length, on idle, and unconditionally at session end. Nothing can be held
 * in the buffer indefinitely.
 *
 * <h2>Threading</h2>
 * Not thread-safe by design — one instance per session, owned by the caller, which is
 * how the sink chain already serialises per-session results. Callers that share an
 * instance across threads must synchronise.
 */
public final class SentenceAssembler {

    /** Closed because the text reached a sentence terminator — the good case. */
    public static final String REASON_PUNCTUATION = "punctuation";

    /** Closed because the buffered speech got long without a terminator. */
    public static final String REASON_MAX_DURATION = "max_duration";

    /** Closed because the buffered text got long without a terminator. */
    public static final String REASON_MAX_LENGTH = "max_length";

    /** Closed because the speaker stopped producing committed text. */
    public static final String REASON_IDLE = "idle";

    /** Closed because the recording ended — the buffer is never carried past the session. */
    public static final String REASON_SESSION_END = "session_end";

    private final SentenceAssemblyPolicy policy;
    private final List<String> bufferedEventIds = new ArrayList<>();
    private final StringBuilder buffer = new StringBuilder();

    private long bufferStartedAtMs;
    private long bufferEndedAtMs;
    /** Gateway receipt clock of the last folded fragment — bounds the trailing line. */
    private long lastReceiptAtMs;
    /** Source-audio end of the last folded fragment — detects a pause by the speaker. */
    private long lastSpeechEndMs;
    private int bufferSpeechMs;

    public SentenceAssembler(final SentenceAssemblyPolicy policy) {
        this.policy = policy == null ? SentenceAssemblyPolicy.defaults() : policy;
    }

    /**
     * A committed transcript fragment, already reordered into source sequence.
     *
     * @param eventId id of the committed event this fragment came from
     * @param text committed transcript text; blank fragments are ignored entirely
     * @param startedAtMs window start — provenance only (may be 0 or an arbitrary past
     *     value per the gateway contract), never used for idle timing
     * @param endedAtMs window end — provenance only
     * @param speechDurationMs audio duration of this fragment; non-positive values fall
     *     back to the wall-clock window length
     * @param receiptAtMs gateway wall-clock when the fragment was received. This is the
     *     ONLY clock idle timing uses, so the sweep's {@code now} and a fragment's
     *     timestamp are always in the same time base — the source window timestamps are
     *     not trustworthy for elapsed time and mixing the two would flush a fresh line
     *     on the first sweep.
     */
    public record Fragment(
            String eventId,
            String text,
            long startedAtMs,
            long endedAtMs,
            int speechDurationMs,
            long receiptAtMs) {}

    /**
     * Offer a committed fragment. The caller must offer fragments in source order (see
     * {@link ChunkReorderBuffer}); the assembler folds in the order it receives them.
     *
     * @return the lines that closed as a result — usually empty (still buffering), one
     *     when this fragment completed a line, or two when an idle gap closed the
     *     previous line before this fragment opened a new one
     */
    public List<AssembledUtterance> offer(final Fragment fragment) {
        if (fragment == null) {
            return List.of();
        }
        final String text = fragment.text() == null ? "" : fragment.text().strip();
        if (text.isEmpty()) {
            // Nothing to fold. Do not record the id either — a line's source list must
            // only name fragments whose text is actually in it.
            return List.of();
        }

        final List<AssembledUtterance> closed = new ArrayList<>(2);
        // A pause the SPEAKER made closes what came before it: the silence belongs
        // between the lines, not inside the new one. That is a source-audio question,
        // so it is measured on source timestamps — and only when they are usable.
        if (isSpeechGap(fragment)) {
            closed.add(close(REASON_IDLE));
        }

        append(fragment, text);

        if (SentenceBoundary.endsSentence(text)) {
            closed.add(close(REASON_PUNCTUATION));
        } else if (bufferSpeechMs >= policy.maxSpeechMs()) {
            closed.add(close(REASON_MAX_DURATION));
        } else if (buffer.length() >= policy.maxChars()) {
            closed.add(close(REASON_MAX_LENGTH));
        }
        return List.copyOf(closed);
    }

    /**
     * Close the buffer when no fragment has been RECEIVED for
     * {@link SentenceAssemblyPolicy#idleMs()} — the speaker has stopped and a trailing
     * line should not wait for a fragment that will never come.
     *
     * <p>{@code nowMs} and the timestamp it is compared against are both on the gateway
     * receipt clock. Source window timestamps are never mixed in: the contract allows
     * them to be zero or arbitrary, and comparing one against wall-clock time would flush
     * a freshly-opened line on the very first sweep.
     *
     * @param nowMs gateway receipt clock
     */
    public Optional<AssembledUtterance> flushIfIdle(final long nowMs) {
        if (buffer.length() == 0) {
            return Optional.empty();
        }
        if (nowMs - lastReceiptAtMs < policy.idleMs()) {
            return Optional.empty();
        }
        return Optional.of(close(REASON_IDLE));
    }

    /**
     * Whether the speaker paused between the buffered text and this fragment.
     *
     * <p>Measured on source audio timestamps, because it asks about silence in the
     * recording rather than elapsed processing time — a fragment holding 5 s of speech
     * takes 5 s of wall clock to arrive without the speaker having paused at all.
     *
     * <p>Returns false whenever the timestamps are not usable (zero, non-monotonic, or
     * no previous fragment), so an unreliable source clock degrades to "no split" rather
     * than to spurious splits. The receipt-clock idle in {@link #flushIfIdle} still
     * bounds the line.
     */
    private boolean isSpeechGap(final Fragment fragment) {
        if (buffer.length() == 0 || lastSpeechEndMs <= 0 || fragment.startedAtMs() <= 0) {
            return false;
        }
        return fragment.startedAtMs() - lastSpeechEndMs >= policy.idleMs();
    }

    /**
     * Close the buffer unconditionally — the recording ended.
     *
     * @return the trailing line, or empty when nothing was buffered
     */
    public Optional<AssembledUtterance> closeSession() {
        if (buffer.length() == 0) {
            return Optional.empty();
        }
        return Optional.of(close(REASON_SESSION_END));
    }

    /** Whether anything is currently buffered — test/observability helper. */
    public boolean hasBufferedText() {
        return buffer.length() > 0;
    }

    private void append(final Fragment fragment, final String text) {
        if (buffer.length() == 0) {
            bufferStartedAtMs = fragment.startedAtMs();
        } else {
            buffer.append(' ');
        }
        buffer.append(text);
        bufferedEventIds.add(fragment.eventId());
        bufferEndedAtMs = fragment.endedAtMs();
        // Two clocks, never compared against each other: the receipt clock bounds how
        // long a trailing line waits, the source clock detects a pause in the speech.
        lastReceiptAtMs = fragment.receiptAtMs();
        lastSpeechEndMs = Math.max(fragment.endedAtMs(), fragment.startedAtMs());
        bufferSpeechMs += speechMsOf(fragment);
    }

    private static int speechMsOf(final Fragment fragment) {
        if (fragment.speechDurationMs() > 0) {
            return fragment.speechDurationMs();
        }
        final long wallClock = fragment.endedAtMs() - fragment.startedAtMs();
        return wallClock > 0 ? (int) Math.min(wallClock, Integer.MAX_VALUE) : 0;
    }

    private AssembledUtterance close(final String reason) {
        final AssembledUtterance utterance =
                new AssembledUtterance(
                        buffer.toString(),
                        List.copyOf(bufferedEventIds),
                        bufferStartedAtMs,
                        bufferEndedAtMs,
                        bufferSpeechMs,
                        reason);
        buffer.setLength(0);
        bufferedEventIds.clear();
        bufferSpeechMs = 0;
        bufferStartedAtMs = 0;
        bufferEndedAtMs = 0;
        return utterance;
    }
}
