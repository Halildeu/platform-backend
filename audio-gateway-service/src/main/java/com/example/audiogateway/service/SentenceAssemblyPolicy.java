package com.example.audiogateway.service;

/**
 * Bounds that guarantee a {@link SentenceAssembler} line always closes, even when the
 * speaker never produces a sentence terminator.
 *
 * <p>The policy lives in the gateway, not in each client, so desktop and web render
 * identical lines and the rule cannot drift between the two repositories.
 *
 * @param maxSpeechMs close after this much buffered speech (default 12 s — long enough
 *     to hold a spoken sentence together, short enough that a viewer is never left
 *     watching a stalled line)
 * @param maxChars close after this much buffered text (default 200 — a line beyond this
 *     stops being easier to read than the fragments it replaced)
 * @param idleMs close when committed speech stops for this long (default 2 s — a pause
 *     this long is a boundary the speaker made, whether or not they punctuated it)
 */
public record SentenceAssemblyPolicy(int maxSpeechMs, int maxChars, long idleMs) {

    public SentenceAssemblyPolicy {
        if (maxSpeechMs <= 0) {
            throw new IllegalArgumentException("maxSpeechMs must be > 0");
        }
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be > 0");
        }
        if (idleMs <= 0) {
            throw new IllegalArgumentException("idleMs must be > 0");
        }
    }

    public static SentenceAssemblyPolicy defaults() {
        return new SentenceAssemblyPolicy(12_000, 200, 2_000L);
    }
}
