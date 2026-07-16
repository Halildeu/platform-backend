package com.example.audiogateway.service;

import com.example.audiogateway.dto.AudioFormat;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds the audio a session may hold inside this gateway — #428 / platform-ai#257.
 *
 * <h2>What it counts, and why not Redis</h2>
 * The live-code measurement behind the #257 owner decision established that the Redis
 * stream carries no audio at all — only a SHA-256 hash and routing metadata. So Redis
 * length can never be an audio backlog, and {@code maxBufferedSeconds} is not enforceable
 * there. The audio that actually exists in this process is the Direct-STT lifecycle, and
 * that is what this accountant bounds, per {@code tenantId + sessionId}:
 *
 * <ul>
 *   <li>PCM held in {@link DirectSttAudioWindowAggregator} awaiting its window flush;</li>
 *   <li>windows handed to the forward scheduler and still queued;</li>
 *   <li>windows whose HTTP request to live-stt is in flight.</li>
 * </ul>
 *
 * <p>All three are "accepted but not terminally released" — audio this process is still
 * responsible for. The Redis descriptor path keeps its own, separate limits
 * ({@code stream-max-len}, pending-count, oldest-idle); those are descriptor backpressure
 * and are deliberately NOT called audio duration.
 *
 * <h2>Reservation, not a counter of things already done</h2>
 * Capacity is taken BEFORE the audio is admitted, not measured after: a chunk is charged
 * at admission and refunded only when it can no longer consume memory. Charging afterwards
 * would let a burst pass the check and then exceed the bound. The reservation is acquired
 * after the idempotency/replay decision and before any registry mutation, byte copy or
 * queue handoff, so a rejected chunk leaves no trace and an admitted one is already paid
 * for. Callers get a {@link Reservation} handle whose {@link Reservation#release()} is
 * idempotent, because the terminal paths (success, error, timeout, saturation drop,
 * cancel, shutdown) can and do overlap.
 *
 * <h2>Scope: this process, on purpose</h2>
 * The state here is in-memory and process-local, which is CORRECT rather than a shortcut:
 * the thing being bounded — raw PCM in this JVM's heap — is itself process-local. A
 * restart drops the buffered audio and these reservations together, so there is no durable
 * counter to drift out of step with reality, which is exactly the failure a Redis-side
 * counter would have introduced.
 *
 * <p><b>This counter is not cluster-global and must never be presented as one.</b> With
 * more than one gateway replica and no session affinity, each replica bounds only the
 * audio it is holding. Sticky sessions or a shared atomic reservation are a separate
 * acceptance gate before multi-replica rollout (#257 owner decision).
 *
 * <h2>Thread-safety</h2>
 * Every method is {@code synchronized}. {@code reserve} is already called under
 * {@link InMemoryAudioSessionRegistry}'s admission monitor, but {@code release} arrives
 * from forward-scheduler and HTTP-completion threads, so the map needs its own guard —
 * and check-then-act over the reserved total must be atomic or two concurrent producers
 * both see room and both get in.
 */
final class DirectSttAudioAccountant {

    private static final int PCM16_BYTES_PER_SAMPLE = 2;

    /** The bound, in seconds of real audio, applied per tenant+session. */
    private final int maxBufferedSeconds;

    /** Reserved sample frames per session. A session is absent when it holds nothing. */
    private final Map<SessionKey, Long> reservedFrames = new HashMap<>();

    DirectSttAudioAccountant(final int maxBufferedSeconds) {
        if (maxBufferedSeconds <= 0) {
            throw new IllegalArgumentException(
                    "maxBufferedSeconds must be > 0 but was " + maxBufferedSeconds);
        }
        this.maxBufferedSeconds = maxBufferedSeconds;
    }

    /** A tenant-scoped session. Tenant is part of the key so two tenants can never share a bound. */
    record SessionKey(long tenantId, String sessionId) {
        SessionKey {
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    /** The outcome of asking for capacity. */
    sealed interface ReserveOutcome {

        /**
         * Capacity was available and {@code frames} are now charged to the session.
         *
         * <p>No handle: a charge is refunded by whoever ends up holding the BYTES, and that
         * is not the chunk. {@link DirectSttAudioWindowAggregator} slices chunks at window
         * boundaries, so one chunk's audio can leave in two different windows and one
         * window can carry parts of several chunks. A per-chunk handle would therefore
         * have to be split to be released, which is why the refund is a frame count
         * ({@link #refundHandle}) owned by the window, and the charge is just a number.
         *
         * @param frames    sample frames charged
         * @param durationUs the same audio as real time — for logs and metrics, never the decision
         */
        record Reserved(long frames, long durationUs) implements ReserveOutcome {
        }

        /**
         * The session is holding its bound and this chunk would exceed it — a MEASURED
         * refusal, which the caller surfaces as 429 + Retry-After. The audio is not lost:
         * the client retries once the in-flight windows drain.
         */
        record OverLimit(long reservedFrames, long requestedFrames, long limitFrames)
                implements ReserveOutcome {
        }

        /**
         * Capacity could not be measured — an unmeterable format, a nonsensical sample
         * rate, a byte length that is not whole PCM frames.
         *
         * <p>Deliberately NOT the same as "there is room". The caller surfaces 503 rather
         * than admitting the chunk, because admitting audio whose duration is unknown is
         * how an unbounded buffer grows while the gauge reads zero. Fail-open here would
         * defeat the entire bound.
         */
        record Unmeterable(String reason) implements ReserveOutcome {
        }
    }

    /**
     * A refund of a known frame count, applied exactly once.
     *
     * <p>The single-shot guard is the whole point. The terminal paths of one forward
     * overlap in practice: a request can time out and then complete, a scheduled task can
     * be rejected while shutdown discards the same buffer, saturation can drop a window
     * that a later error handler also sees. Each of those calls release. Without the
     * guard the second call would refund audio that was never charged, the session would
     * drift below zero, and the bound would silently widen for everyone after it.
     *
     * <p>Held by whoever holds the bytes — a queued/in-flight window, or the chunk itself
     * on a path that never reaches the aggregator.
     */
    static final class Refund {

        private final DirectSttAudioAccountant accountant;
        private final SessionKey key;
        private final long frames;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Refund(final DirectSttAudioAccountant accountant, final SessionKey key, final long frames) {
            this.accountant = accountant;
            this.key = key;
            this.frames = frames;
        }

        /** Give the frames back. Safe from any thread, any number of times. */
        void release() {
            if (released.compareAndSet(false, true)) {
                accountant.releaseFrames(key, frames);
            }
        }

        boolean isReleased() {
            return released.get();
        }

        long frames() {
            return frames;
        }

        SessionKey key() {
            return key;
        }
    }

    /**
     * A one-shot refund for frames already charged to a session.
     *
     * <p>Creating a handle does not charge anything — {@link #reserve} did that. This just
     * names who is responsible for giving those frames back, which is why the aggregator
     * can charge per chunk and refund per window without ever double-counting: the frames
     * move from "buffered" to "queued/in-flight" without a second reservation, exactly as
     * the #257 owner decision requires.
     */
    Refund refundHandle(final long tenantId, final String sessionId, final long frames) {
        return new Refund(this, new SessionKey(tenantId, sessionId), frames);
    }

    /** Frames of PCM16 in {@code byteLength} for this session's shape, or -1 if not whole frames. */
    static long framesIn(final int byteLength, final int channels) {
        if (channels <= 0 || byteLength < 0) {
            return -1L;
        }
        final int bytesPerFrame = channels * PCM16_BYTES_PER_SAMPLE;
        return byteLength % bytesPerFrame == 0 ? byteLength / bytesPerFrame : -1L;
    }

    /**
     * Charge {@code byteLength} of PCM16 to a session, if it fits.
     *
     * <p>Counts sample FRAMES rather than bytes: a session's sample rate and channel count
     * are fixed for its lifetime, so frames convert to real seconds exactly, with no
     * rounding to argue about at the limit. Bytes would not — 16 kHz mono and 48 kHz
     * stereo disagree by 6x on what a byte is worth.
     *
     * @param audioFormat  the session's declared format; only PCM16 is meterable here
     * @param sampleRateHz the session's sample rate
     * @param channels     the session's channel count
     * @param byteLength   raw PCM16 byte length of this chunk
     */
    synchronized ReserveOutcome reserve(
            final long tenantId,
            final String sessionId,
            final AudioFormat audioFormat,
            final int sampleRateHz,
            final int channels,
            final int byteLength) {

        // Validation runs BEFORE any short-circuit so a nonsensical input is reported as
        // unmeterable rather than silently charged as zero.
        if (sessionId == null || sessionId.isBlank()) {
            return new ReserveOutcome.Unmeterable("sessionId missing");
        }
        if (audioFormat != AudioFormat.PCM16) {
            // The #257 decision: direct-STT/live mode is PCM16-only. WAV/WEBM_OPUS stay in
            // the global API contract, but their duration cannot be derived without a
            // container/codec parser, and accepting audio we cannot meter would leave the
            // bound unenforced. This is a 503, not a rejection of the format itself.
            return new ReserveOutcome.Unmeterable(
                    "format=" + (audioFormat == null ? "null" : audioFormat.name()));
        }
        if (sampleRateHz <= 0) {
            return new ReserveOutcome.Unmeterable("sampleRateHz=" + sampleRateHz);
        }
        if (channels <= 0) {
            return new ReserveOutcome.Unmeterable("channels=" + channels);
        }
        if (byteLength < 0) {
            return new ReserveOutcome.Unmeterable("byteLength=" + byteLength);
        }

        final int bytesPerFrame = channels * PCM16_BYTES_PER_SAMPLE;
        if (byteLength % bytesPerFrame != 0) {
            // A partial frame means the stream is not what it claims to be; metering it
            // would silently round real audio away.
            return new ReserveOutcome.Unmeterable(
                    "byteLength=" + byteLength + " is not whole PCM16 frames of " + bytesPerFrame);
        }

        final long frames = byteLength / bytesPerFrame;
        final long limitFrames = (long) maxBufferedSeconds * sampleRateHz;
        final SessionKey key = new SessionKey(tenantId, sessionId);
        final long current = reservedFrames.getOrDefault(key, 0L);

        // Exact limit is ACCEPTED; one frame beyond is refused. The bound is a ceiling the
        // session may sit on, not one it must stay under.
        if (current + frames > limitFrames) {
            return new ReserveOutcome.OverLimit(current, frames, limitFrames);
        }

        reservedFrames.put(key, current + frames);
        final long durationUs = (frames * 1_000_000L) / sampleRateHz;
        return new ReserveOutcome.Reserved(frames, durationUs);
    }

    /** Reserved frames for a session — for tests and the gauge. */
    synchronized long reservedFrames(final long tenantId, final String sessionId) {
        return reservedFrames.getOrDefault(new SessionKey(tenantId, sessionId), 0L);
    }

    /** Total reserved frames across every session — for the gauge. */
    synchronized long totalReservedFrames() {
        return reservedFrames.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Sessions currently holding a reservation — for the gauge. */
    synchronized int activeSessions() {
        return reservedFrames.size();
    }

    /**
     * How many frames a session may still take. Exposed for tests and diagnostics; the
     * decision itself is made inside {@link #reserve} under the same lock, because asking
     * here and reserving later would be exactly the check-then-act race the lock exists to
     * prevent.
     */
    synchronized long remainingFrames(final long tenantId, final String sessionId, final int sampleRateHz) {
        final long limitFrames = (long) maxBufferedSeconds * sampleRateHz;
        return Math.max(0L, limitFrames - reservedFrames(tenantId, sessionId));
    }

    /**
     * Whether the last release drove a session's total below zero — an invariant breach
     * that means some path released a charge it never took. Never silently floored: the
     * caller raises it as an alarm.
     */
    synchronized boolean hasNegativeInvariantBreach() {
        return negativeInvariantBreaches > 0;
    }

    synchronized long negativeInvariantBreaches() {
        return negativeInvariantBreaches;
    }

    private long negativeInvariantBreaches;

    private synchronized void releaseFrames(final SessionKey key, final long frames) {
        final long current = reservedFrames.getOrDefault(key, 0L);
        final long next = current - frames;
        if (next < 0) {
            // Do NOT floor to zero and carry on: that would hide a double-release behind a
            // plausible-looking gauge. Clear the session and count the breach so it alarms.
            negativeInvariantBreaches++;
            reservedFrames.remove(key);
            return;
        }
        if (next == 0) {
            // Drop the entry entirely rather than leave a zero: a session that finished must
            // not keep occupying the map for the life of the process.
            reservedFrames.remove(key);
        } else {
            reservedFrames.put(key, next);
        }
    }

    /**
     * Drop every reservation for a session — for when the session's audio is gone.
     *
     * <p><b>NOT yet wired to a server-side abandon/expiry path — tracked in
     * platform-backend#841 (#428 follow-up), a rollout condition of #428.</b> Today the
     * only callers are {@link #discardAll} at shutdown and the tests. A session that
     * streams PCM and then disconnects WITHOUT an explicit finish keeps its reservation
     * (and its aggregator slot) until process restart — enough abandoned sessions push new
     * ones into capacity drop. Closing that gap means binding the registry's
     * session-removal terminal to {@code aggregator.discard} + this method, which #841
     * tracks rather than being claimed here.
     *
     * <p>Even once wired, this is a leak safety net, not a substitute for the per-window
     * refund: a reservation whose forward is still in flight must NOT be un-charged here
     * while its bytes are on the heap.
     */
    synchronized long discardSession(final long tenantId, final String sessionId) {
        final Long dropped = reservedFrames.remove(new SessionKey(tenantId, sessionId));
        return dropped == null ? 0L : dropped;
    }

    /** Drop all state — process shutdown, and the reset a restart gives for free. */
    synchronized void discardAll() {
        reservedFrames.clear();
    }

    int maxBufferedSeconds() {
        return maxBufferedSeconds;
    }
}
