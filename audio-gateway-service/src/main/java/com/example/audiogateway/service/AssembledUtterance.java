package com.example.audiogateway.service;

import java.util.List;

/**
 * One readable transcript line assembled from consecutive committed STT fragments
 * (Faz 24 — transcript readability).
 *
 * <p>{@link #sourceEventIds()} is the audit trail: it names every raw fragment folded
 * into this line, in arrival order, so a viewer line can always be traced back to the
 * committed events it came from and a later revision can bind to the same sources
 * instead of silently mutating the line.
 *
 * @param text the fragments' text joined with a single space; whitespace-normalised and
 *     otherwise unmodified — no punctuation is invented, no word is corrected, nothing is
 *     dropped or reordered
 * @param sourceEventIds ids of the committed fragments folded into this line, in order
 * @param startedAtMs window start of the first fragment
 * @param endedAtMs window end of the last fragment
 * @param speechDurationMs summed audio duration of the folded fragments
 * @param flushReason why the line closed — one of {@link SentenceAssembler#REASON_PUNCTUATION},
 *     {@link SentenceAssembler#REASON_MAX_DURATION}, {@link SentenceAssembler#REASON_MAX_LENGTH},
 *     {@link SentenceAssembler#REASON_IDLE}, {@link SentenceAssembler#REASON_SESSION_END}
 */
public record AssembledUtterance(
        String text,
        List<String> sourceEventIds,
        long startedAtMs,
        long endedAtMs,
        int speechDurationMs,
        String flushReason) {

    public AssembledUtterance {
        sourceEventIds = List.copyOf(sourceEventIds);
    }

    /** Character count — PII-safe size signal (never the text itself). */
    public int textLength() {
        return text == null ? 0 : text.length();
    }
}
