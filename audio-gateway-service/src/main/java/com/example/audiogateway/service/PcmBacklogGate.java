package com.example.audiogateway.service;

import com.example.audiogateway.service.AudioChunkDispatcher.DispatchOutcome;

/**
 * Server-observed undelivered-audio backlog gate — the {@code audio.gateway.bounds
 * .max-buffered-seconds} enforcement semantics (#428 owner decision, acceptance #1-#2).
 *
 * <p><b>What the bound measures.</b> The backlog is the audio the gateway has already
 * accepted from the client but that has NOT yet been delivered to the STT consumer,
 * expressed as PCM16 duration:
 *
 * <pre>{@code seconds = pendingBytes / (sampleRateHz * channels * 2)}</pre>
 *
 * <p><b>What it deliberately does NOT measure.</b> {@code chunkStartedAtMs} is a
 * client-supplied header and is never an input here. Existing admission/dispatch contract
 * fixtures send it as an arbitrary ordering value (literal {@code 0}, or a constant far in
 * the past); a staleness rule built on it would reject valid chunks. Acceptance #1 requires
 * exactly that an arbitrary or old {@code chunkStartedAtMs} must NOT reject a valid chunk,
 * so the signal is server-observed byte accounting instead.
 *
 * <p><b>Counter ownership and the ACK contract (acceptance #1, this slice).</b> The gate is
 * a pure decision function; it does not own the counter. The authoritative owner of
 * {@code pendingBytes} is the dispatcher that holds the undelivered audio:
 * <ul>
 *   <li><b>Redis producer path</b> — the gateway increments an atomic per-partition byte
 *       counter by the chunk length as part of publishing, and the STT consumer
 *       ({@code live-stt-service}, platform-ai) decrements it on XACK/dequeue. XLEN alone
 *       is entry <em>count</em>, not bytes, so it cannot express a duration bound. The
 *       consumer-side decrement is cross-repo and tracked separately.</li>
 *   <li><b>Direct-STT path</b> — the pending window bytes are local and authoritative, and
 *       map onto the same {@code pendingBytes} semantics.</li>
 * </ul>
 *
 * <p><b>Unobservable backlog fails open, by design.</b> {@link #UNKNOWN_PENDING_BYTES}
 * means the dispatcher cannot observe its backlog — e.g. the consumer group does not exist
 * yet, so nothing decrements the counter. A monotonically-growing counter with no consumer
 * would drive a permanent 429 for every session: a self-inflicted outage rather than
 * backpressure. This mirrors the existing consumer-lag gate, which likewise skips when the
 * group is absent. Misconfiguration still fails closed at startup ({@code Bounds.validate}),
 * which is a separate concern from an unobservable runtime signal.
 *
 * <p><b>Gate placement.</b> Callers MUST evaluate this before any state mutation or XADD,
 * so that a rejected chunk leaves no trace and capacity reopens purely on consumer ACK
 * (acceptance #3-#5). Over the limit maps to {@link DispatchOutcome.QueueFull}, which the
 * controller renders as 429 {@code AUDIO_GATEWAY_QUEUE_FULL} + {@code Retry-After}.
 *
 * <p>Tracked by #428. This slice is the shared decision core only; the Redis byte counter,
 * the direct-STT mapping and the call sites land in the follow-up slices.
 */
final class PcmBacklogGate {

    /** Sentinel: the dispatcher cannot observe its backlog, so the gate does not apply. */
    static final long UNKNOWN_PENDING_BYTES = -1L;

    private static final int PCM16_BYTES_PER_SAMPLE = 2;

    private final int maxBufferedSeconds;
    private final long retryAfterSeconds;

    PcmBacklogGate(final int maxBufferedSeconds, final long retryAfterSeconds) {
        if (maxBufferedSeconds <= 0) {
            throw new IllegalArgumentException(
                    "maxBufferedSeconds must be positive, got " + maxBufferedSeconds);
        }
        if (retryAfterSeconds <= 0) {
            throw new IllegalArgumentException(
                    "retryAfterSeconds must be positive, got " + retryAfterSeconds);
        }
        this.maxBufferedSeconds = maxBufferedSeconds;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * Decide whether a chunk may be admitted given the currently undelivered backlog.
     *
     * @param pendingBytes accepted-but-undelivered PCM16 bytes, or
     *                     {@link #UNKNOWN_PENDING_BYTES} when unobservable
     * @param incomingBytes PCM16 byte length of the chunk being admitted
     * @return {@link DispatchOutcome.QueueFull} when admitting would exceed the bound,
     *         otherwise {@code null} meaning "pass, continue dispatching"
     */
    DispatchOutcome check(final long pendingBytes, final int incomingBytes,
            final int sampleRateHz, final int channels) {
        if (pendingBytes == UNKNOWN_PENDING_BYTES) {
            return null;
        }
        if (pendingBytes < 0) {
            throw new IllegalArgumentException("pendingBytes must not be negative, got " + pendingBytes);
        }
        if (incomingBytes < 0) {
            throw new IllegalArgumentException("incomingBytes must not be negative, got " + incomingBytes);
        }
        final long bytesPerSecond = bytesPerSecond(sampleRateHz, channels);
        // The bound is on the backlog after this chunk lands: admitting a chunk that would
        // cross the limit is what must be refused, while landing exactly on the limit is
        // still within budget (acceptance #2: accept at the limit, 429 above it).
        final long projectedBytes = Math.addExact(pendingBytes, (long) incomingBytes);
        final long limitBytes = Math.multiplyExact((long) maxBufferedSeconds, bytesPerSecond);
        if (projectedBytes > limitBytes) {
            return new DispatchOutcome.QueueFull(retryAfterSeconds);
        }
        return null;
    }

    /** Backlog duration in whole seconds — observability/logging helper, never a gate input. */
    static long bufferedSeconds(final long pendingBytes, final int sampleRateHz, final int channels) {
        if (pendingBytes <= 0) {
            return 0L;
        }
        return pendingBytes / bytesPerSecond(sampleRateHz, channels);
    }

    private static long bytesPerSecond(final int sampleRateHz, final int channels) {
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be positive, got " + sampleRateHz);
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive, got " + channels);
        }
        return (long) sampleRateHz * channels * PCM16_BYTES_PER_SAMPLE;
    }
}
