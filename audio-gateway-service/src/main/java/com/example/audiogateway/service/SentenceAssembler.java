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
 * <h2>Losslessness</h2>
 * Every fragment offered is emitted in exactly one line, in arrival order, with its text
 * unmodified apart from a single separating space. This layer never deletes a repeat,
 * corrects a word, invents punctuation, or calls an LLM. That keeps the assembled line
 * usable as the verbatim record.
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
    private long lastFragmentAtMs;
    private int bufferSpeechMs;

    public SentenceAssembler(final SentenceAssemblyPolicy policy) {
        this.policy = policy == null ? SentenceAssemblyPolicy.defaults() : policy;
    }

    /**
     * A committed transcript fragment.
     *
     * @param eventId id of the committed event this fragment came from
     * @param text committed transcript text; blank fragments are ignored entirely
     * @param startedAtMs window start
     * @param endedAtMs window end
     * @param speechDurationMs audio duration of this fragment; non-positive values fall
     *     back to the wall-clock window length
     */
    public record Fragment(
            String eventId, String text, long startedAtMs, long endedAtMs, int speechDurationMs) {}

    /**
     * Offer a committed fragment.
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
        // An idle gap closes what came before it: the pause belongs between the lines,
        // not inside the new one.
        flushIfIdle(fragment.startedAtMs()).ifPresent(closed::add);

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
     * Close the buffer if no fragment has arrived for {@link SentenceAssemblyPolicy#idleMs()}.
     *
     * <p>Callers that only invoke this from {@link #offer} get idle detection between
     * fragments; a periodic caller additionally bounds how long a trailing line waits
     * after the speaker stops.
     */
    public Optional<AssembledUtterance> flushIfIdle(final long nowMs) {
        if (buffer.length() == 0) {
            return Optional.empty();
        }
        if (nowMs - lastFragmentAtMs < policy.idleMs()) {
            return Optional.empty();
        }
        return Optional.of(close(REASON_IDLE));
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
        // Idle is measured from when committed speech last ENDED. Measuring from the
        // start would make any fragment longer than the idle threshold look like a
        // pause and split the sentence it was supposed to hold together.
        lastFragmentAtMs = Math.max(fragment.endedAtMs(), fragment.startedAtMs());
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
