package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.service.DirectSttAudioAccountant.ReserveOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The Direct-STT audio bound — #428 / platform-ai#257 owner decision.
 *
 * <p>Covers the acceptance order the owner decision names: exact-limit/over-limit,
 * concurrency, release on every terminal path, capacity reopen, and restart.
 */
class DirectSttAudioAccountantTest {

    private static final long TENANT = 42L;
    private static final String SESSION = "sess-1";
    private static final int RATE_16K = 16_000;
    private static final int MONO = 1;
    private static final int MAX_SECONDS = 30;

    /** Bytes of 16 kHz mono PCM16 for a given number of seconds. */
    private static int seconds16kMono(final double seconds) {
        return (int) Math.round(seconds * RATE_16K) * MONO * 2;
    }

    private DirectSttAudioAccountant accountant() {
        return new DirectSttAudioAccountant(MAX_SECONDS);
    }

    private static ReserveOutcome reserve16k(
            final DirectSttAudioAccountant a, final String sessionId, final int byteLength) {
        return a.reserve(TENANT, sessionId, AudioFormat.PCM16, RATE_16K, MONO, byteLength);
    }

    // ────────────────────────── exact limit / over limit ──────────────────────────

    @Test
    void exactlyTheLimitIsAccepted() {
        DirectSttAudioAccountant a = accountant();

        ReserveOutcome outcome = reserve16k(a, SESSION, seconds16kMono(MAX_SECONDS));

        assertThat(outcome).isInstanceOf(ReserveOutcome.Reserved.class);
        assertThat(a.reservedFrames(TENANT, SESSION)).isEqualTo((long) MAX_SECONDS * RATE_16K);
    }

    @Test
    void oneFrameBeyondTheLimitIsRefused() {
        DirectSttAudioAccountant a = accountant();
        // The bound is a ceiling the session may sit on; the very next frame is not free.
        int oneFrameOver = seconds16kMono(MAX_SECONDS) + (MONO * 2);

        ReserveOutcome outcome = reserve16k(a, SESSION, oneFrameOver);

        assertThat(outcome).isInstanceOf(ReserveOutcome.OverLimit.class);
        assertThat(a.reservedFrames(TENANT, SESSION))
                .as("a refused reservation must charge nothing")
                .isZero();
    }

    @Test
    void reservationsAccumulateUntilTheLimitThenRefuse() {
        DirectSttAudioAccountant a = accountant();
        for (int i = 0; i < 6; i++) {
            assertThat(reserve16k(a, SESSION, seconds16kMono(5)))
                    .as("5s chunk #%d of a 30s bound", i + 1)
                    .isInstanceOf(ReserveOutcome.Reserved.class);
        }

        assertThat(reserve16k(a, SESSION, seconds16kMono(0.001)))
                .as("the session is exactly full; nothing more fits")
                .isInstanceOf(ReserveOutcome.OverLimit.class);
    }

    @Test
    void overLimitReportsWhatItSaw_soTheRejectionIsDiagnosable() {
        DirectSttAudioAccountant a = accountant();
        reserve16k(a, SESSION, seconds16kMono(28));

        ReserveOutcome outcome = reserve16k(a, SESSION, seconds16kMono(5));

        assertThat(outcome).isInstanceOfSatisfying(ReserveOutcome.OverLimit.class, over -> {
            assertThat(over.reservedFrames()).isEqualTo(28L * RATE_16K);
            assertThat(over.requestedFrames()).isEqualTo(5L * RATE_16K);
            assertThat(over.limitFrames()).isEqualTo((long) MAX_SECONDS * RATE_16K);
        });
    }

    // ────────────────────────── unmeterable → never "there is room" ──────────────────────────

    @Test
    void nonPcm16IsUnmeterable_notAdmittedAsIfItWereFree() {
        DirectSttAudioAccountant a = accountant();
        // 30 seconds of Opus is a few KB. Metering it as PCM16 bytes would read ~2 seconds
        // and let a session hold 15x its bound — the original defect this replaces.
        for (AudioFormat compressed : List.of(AudioFormat.WEBM_OPUS, AudioFormat.WAV)) {
            ReserveOutcome outcome =
                    a.reserve(TENANT, SESSION, compressed, RATE_16K, MONO, 4_096);

            assertThat(outcome)
                    .as("%s must be unmeterable in direct-STT mode", compressed)
                    .isInstanceOfSatisfying(ReserveOutcome.Unmeterable.class,
                            u -> assertThat(u.reason()).contains(compressed.name()));
        }
        assertThat(a.totalReservedFrames()).isZero();
    }

    @Test
    void nonsensicalRateOrChannelsAreUnmeterable_notZeroCost() {
        DirectSttAudioAccountant a = accountant();

        assertThat(a.reserve(TENANT, SESSION, AudioFormat.PCM16, 0, MONO, 3_200))
                .isInstanceOfSatisfying(ReserveOutcome.Unmeterable.class,
                        u -> assertThat(u.reason()).contains("sampleRateHz=0"));
        assertThat(a.reserve(TENANT, SESSION, AudioFormat.PCM16, RATE_16K, 0, 3_200))
                .isInstanceOfSatisfying(ReserveOutcome.Unmeterable.class,
                        u -> assertThat(u.reason()).contains("channels=0"));
        assertThat(a.reserve(TENANT, SESSION, AudioFormat.PCM16, RATE_16K, MONO, -1))
                .isInstanceOfSatisfying(ReserveOutcome.Unmeterable.class,
                        u -> assertThat(u.reason()).contains("byteLength=-1"));
    }

    @Test
    void aPartialPcmFrameIsUnmeterable() {
        DirectSttAudioAccountant a = accountant();
        // Stereo: 4 bytes per frame. 4097 is not whole frames — the stream is not what it claims.
        assertThat(a.reserve(TENANT, SESSION, AudioFormat.PCM16, RATE_16K, 2, 4_097))
                .isInstanceOfSatisfying(ReserveOutcome.Unmeterable.class,
                        u -> assertThat(u.reason()).contains("whole PCM16 frames"));
    }

    @Test
    void validationRunsBeforeAnyShortCircuit_soBadInputIsNeverChargedAsZero() {
        DirectSttAudioAccountant a = accountant();
        // A zero-length chunk is meterable and free; a negative one is corrupt. The two
        // must not collapse into the same "costs nothing" answer.
        assertThat(reserve16k(a, SESSION, 0)).isInstanceOf(ReserveOutcome.Reserved.class);
        assertThat(reserve16k(a, SESSION, -2)).isInstanceOf(ReserveOutcome.Unmeterable.class);
    }

    // ────────────────────────── release / capacity reopen ──────────────────────────

    /** The refund the caller would hand to whatever ends up holding these frames. */
    private static DirectSttAudioAccountant.Refund refundFor(
            final DirectSttAudioAccountant a, final String sessionId, final ReserveOutcome outcome) {
        return a.refundHandle(TENANT, sessionId, ((ReserveOutcome.Reserved) outcome).frames());
    }

    @Test
    void releaseReopensCapacity() {
        DirectSttAudioAccountant a = accountant();
        ReserveOutcome full = reserve16k(a, SESSION, seconds16kMono(MAX_SECONDS));
        assertThat(reserve16k(a, SESSION, seconds16kMono(1))).isInstanceOf(ReserveOutcome.OverLimit.class);

        refundFor(a, SESSION, full).release();

        assertThat(a.reservedFrames(TENANT, SESSION)).isZero();
        assertThat(reserve16k(a, SESSION, seconds16kMono(MAX_SECONDS)))
                .as("the whole bound is available again once the audio is gone")
                .isInstanceOf(ReserveOutcome.Reserved.class);
    }

    @Test
    void partialReleaseReopensExactlyWhatWasReleased() {
        DirectSttAudioAccountant a = accountant();
        ReserveOutcome five = reserve16k(a, SESSION, seconds16kMono(5));
        reserve16k(a, SESSION, seconds16kMono(25));
        assertThat(reserve16k(a, SESSION, seconds16kMono(1))).isInstanceOf(ReserveOutcome.OverLimit.class);

        refundFor(a, SESSION, five).release();

        assertThat(reserve16k(a, SESSION, seconds16kMono(5)))
                .isInstanceOf(ReserveOutcome.Reserved.class);
        assertThat(reserve16k(a, SESSION, seconds16kMono(0.001)))
                .as("and not one frame more than was released")
                .isInstanceOf(ReserveOutcome.OverLimit.class);
    }

    @Test
    void refundIsIdempotent_soOverlappingTerminalPathsCannotOverRefund() {
        // Success, error, timeout, cancel, saturation and shutdown can race for the same
        // forward. If each refunded, the session would drift below zero and the bound would
        // quietly widen for everyone after it.
        DirectSttAudioAccountant a = accountant();
        ReserveOutcome ten = reserve16k(a, SESSION, seconds16kMono(10));
        DirectSttAudioAccountant.Refund r = refundFor(a, SESSION, ten);

        r.release();
        r.release();
        r.release();

        assertThat(a.reservedFrames(TENANT, SESSION)).isZero();
        assertThat(a.negativeInvariantBreaches())
                .as("a repeated refund is absorbed, not counted as a breach")
                .isZero();
        assertThat(r.isReleased()).isTrue();
    }

    @Test
    void refundingMoreThanWasChargedRaisesAnInvariantBreach_notASilentFloor() {
        // Flooring at zero would hide a double-refund behind a plausible-looking gauge.
        DirectSttAudioAccountant a = accountant();
        ReserveOutcome ten = reserve16k(a, SESSION, seconds16kMono(10));
        DirectSttAudioAccountant.Refund r = refundFor(a, SESSION, ten);
        a.discardAll(); // process shutdown dropped the map underneath this stale handle

        r.release(); // refunds a charge the map no longer holds

        assertThat(a.negativeInvariantBreaches()).isEqualTo(1);
        assertThat(a.hasNegativeInvariantBreach()).isTrue();
        assertThat(a.reservedFrames(TENANT, SESSION)).isZero();
    }

    @Test
    void aFullyRefundedSessionLeavesNoEntryBehind() {
        // A finished session must not occupy the map for the life of the process.
        DirectSttAudioAccountant a = accountant();
        ReserveOutcome one = reserve16k(a, SESSION, seconds16kMono(1));
        assertThat(a.activeSessions()).isEqualTo(1);

        refundFor(a, SESSION, one).release();

        assertThat(a.activeSessions()).isZero();
    }

    // ── charge per chunk, refund per window: the aggregator slices at window boundaries ──

    @Test
    void framesChargedPerChunkAreRefundedPerWindow_withoutDoubleCounting() {
        // The measurement that shaped this API: DirectSttAudioWindowAggregator splits a
        // chunk at window boundaries, so one chunk's audio can leave in two windows and one
        // window can carry parts of several chunks. Charges and refunds therefore never
        // align 1:1 — only the TOTALS do. A per-chunk handle would have to be split to be
        // released; a frame count does not.
        DirectSttAudioAccountant a = accountant();
        // Three 2-second chunks in; the aggregator re-slices them as 5s + 1s windows.
        for (int i = 0; i < 3; i++) {
            assertThat(reserve16k(a, SESSION, seconds16kMono(2))).isInstanceOf(ReserveOutcome.Reserved.class);
        }
        assertThat(a.reservedFrames(TENANT, SESSION)).isEqualTo(6L * RATE_16K);

        a.refundHandle(TENANT, SESSION, 5L * RATE_16K).release();
        assertThat(a.reservedFrames(TENANT, SESSION))
                .as("the 5s window left; the 1s tail is still buffered")
                .isEqualTo(1L * RATE_16K);

        a.refundHandle(TENANT, SESSION, 1L * RATE_16K).release();
        assertThat(a.reservedFrames(TENANT, SESSION)).isZero();
        assertThat(a.negativeInvariantBreaches()).isZero();
    }

    @Test
    void aRefundHandleChargesNothing_theReservationAlreadyDid() {
        // Handing frames from "buffered" to "queued/in-flight" must not re-charge them:
        // the #257 decision requires the aggregator -> queued/in-flight transition to carry
        // the same reservation rather than count a second one.
        DirectSttAudioAccountant a = accountant();
        reserve16k(a, SESSION, seconds16kMono(10));

        a.refundHandle(TENANT, SESSION, 10L * RATE_16K); // created, not released

        assertThat(a.reservedFrames(TENANT, SESSION))
                .as("creating a refund handle is bookkeeping, not a charge")
                .isEqualTo(10L * RATE_16K);
    }

    @Test
    void framesInRejectsPartialFrames() {
        assertThat(DirectSttAudioAccountant.framesIn(3_200, 1)).isEqualTo(1_600L);
        assertThat(DirectSttAudioAccountant.framesIn(3_200, 2)).isEqualTo(800L);
        assertThat(DirectSttAudioAccountant.framesIn(3_201, 2)).isEqualTo(-1L);
        assertThat(DirectSttAudioAccountant.framesIn(-1, 1)).isEqualTo(-1L);
        assertThat(DirectSttAudioAccountant.framesIn(3_200, 0)).isEqualTo(-1L);
    }

    // ────────────────────────── isolation ──────────────────────────

    @Test
    void sessionsDoNotShareABound() {
        DirectSttAudioAccountant a = accountant();
        reserve16k(a, "sess-A", seconds16kMono(MAX_SECONDS));

        assertThat(reserve16k(a, "sess-B", seconds16kMono(MAX_SECONDS)))
                .as("one session filling its bound must not throttle another")
                .isInstanceOf(ReserveOutcome.Reserved.class);
    }

    @Test
    void tenantIsPartOfTheKey_soTwoTenantsCannotCollideOnASessionId() {
        DirectSttAudioAccountant a = accountant();
        a.reserve(1L, "same-id", AudioFormat.PCM16, RATE_16K, MONO, seconds16kMono(MAX_SECONDS));

        assertThat(a.reserve(2L, "same-id", AudioFormat.PCM16, RATE_16K, MONO, seconds16kMono(MAX_SECONDS)))
                .isInstanceOf(ReserveOutcome.Reserved.class);
        assertThat(a.reservedFrames(1L, "same-id")).isEqualTo((long) MAX_SECONDS * RATE_16K);
        assertThat(a.reservedFrames(2L, "same-id")).isEqualTo((long) MAX_SECONDS * RATE_16K);
    }

    @Test
    void differentSampleRatesEachGetTheirOwnRealTimeBound() {
        // The bound is 30 SECONDS, not 30 seconds' worth of some other session's bytes.
        // 48 kHz audio costs 3x the bytes of 16 kHz for the same real duration.
        DirectSttAudioAccountant a = accountant();

        ReserveOutcome at16k = a.reserve(TENANT, "s16", AudioFormat.PCM16, 16_000, MONO,
                MAX_SECONDS * 16_000 * 2);
        ReserveOutcome at48k = a.reserve(TENANT, "s48", AudioFormat.PCM16, 48_000, MONO,
                MAX_SECONDS * 48_000 * 2);

        assertThat(at16k).isInstanceOf(ReserveOutcome.Reserved.class);
        assertThat(at48k).as("30s at 48 kHz is still 30s").isInstanceOf(ReserveOutcome.Reserved.class);
        assertThat(a.reserve(TENANT, "s48", AudioFormat.PCM16, 48_000, MONO, 2))
                .isInstanceOf(ReserveOutcome.OverLimit.class);
    }

    @Test
    void durationIsReportedInRealTime_independentOfSampleRate() {
        DirectSttAudioAccountant a = accountant();

        var r16 = (ReserveOutcome.Reserved) a.reserve(
                TENANT, "s16", AudioFormat.PCM16, 16_000, MONO, 16_000 * 2);
        var r48 = (ReserveOutcome.Reserved) a.reserve(
                TENANT, "s48", AudioFormat.PCM16, 48_000, MONO, 48_000 * 2);

        assertThat(r16.durationUs()).isEqualTo(1_000_000L);
        assertThat(r48.durationUs()).as("one second is one second at any rate").isEqualTo(1_000_000L);
        assertThat(r48.frames()).isEqualTo(48_000L);
        assertThat(r16.frames()).as("the same duration costs fewer frames at 16 kHz").isEqualTo(16_000L);
    }

    // ────────────────────────── concurrency ──────────────────────────

    @Test
    void concurrentReservationsNeverExceedTheLimit() throws Exception {
        // The race the lock exists for: N producers all read "there is room" before any of
        // them charges. Without atomicity every one of them gets in and the bound is a lie.
        DirectSttAudioAccountant a = accountant();
        int threads = 32;
        int oneSecond = seconds16kMono(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (reserve16k(a, SESSION, oneSecond) instanceof ReserveOutcome.Reserved) {
                        accepted.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.get())
                .as("32 one-second chunks against a 30s bound: exactly 30 get in")
                .isEqualTo(MAX_SECONDS);
        assertThat(a.reservedFrames(TENANT, SESSION)).isEqualTo((long) MAX_SECONDS * RATE_16K);
    }

    @Test
    void concurrentRefundsGiveBackEachChargeExactlyOnce() throws Exception {
        DirectSttAudioAccountant a = accountant();
        int chunks = 30;
        List<DirectSttAudioAccountant.Refund> refunds = new ArrayList<>();
        for (int i = 0; i < chunks; i++) {
            refunds.add(refundFor(a, SESSION, reserve16k(a, SESSION, seconds16kMono(1))));
        }

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        try {
            for (DirectSttAudioAccountant.Refund r : refunds) {
                // Every reservation released twice, from two threads — the overlapping
                // terminal paths.
                futures.add(pool.submit(() -> {
                    start.await();
                    r.release();
                    return null;
                }));
                futures.add(pool.submit(() -> {
                    start.await();
                    r.release();
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(a.reservedFrames(TENANT, SESSION)).isZero();
        assertThat(a.negativeInvariantBreaches()).isZero();
        assertThat(a.activeSessions()).isZero();
    }

    // ────────────────────────── session / process lifecycle ──────────────────────────

    @Test
    void restartResetsTheBoundAndTheAudioTogether_soNoCounterOutlivesItsBytes() {
        // The reason a process-local counter is correct here: the thing it counts is raw
        // PCM in THIS heap. A restart drops both at once, so there is no durable counter
        // left believing in audio that no longer exists.
        DirectSttAudioAccountant beforeRestart = accountant();
        reserve16k(beforeRestart, SESSION, seconds16kMono(MAX_SECONDS));
        assertThat(beforeRestart.totalReservedFrames()).isPositive();

        DirectSttAudioAccountant afterRestart = accountant();

        assertThat(afterRestart.totalReservedFrames()).isZero();
        assertThat(afterRestart.activeSessions()).isZero();
        assertThat(reserve16k(afterRestart, SESSION, seconds16kMono(MAX_SECONDS)))
                .isInstanceOf(ReserveOutcome.Reserved.class);
    }

    @Test
    void discardAllClearsEverything_theShutdownPath() {
        DirectSttAudioAccountant a = accountant();
        reserve16k(a, "s1", seconds16kMono(10));
        reserve16k(a, "s2", seconds16kMono(10));

        a.discardAll();

        assertThat(a.totalReservedFrames()).isZero();
        assertThat(a.activeSessions()).isZero();
    }

    // ────────────────────────── configuration ──────────────────────────

    @Test
    void aNonPositiveBoundIsRejectedAtConstruction() {
        // A zero bound would refuse every chunk; a negative one is meaningless. #836 already
        // rejects this at config load — this is the same rule at the enforcement site.
        assertThatThrownBy(() -> new DirectSttAudioAccountant(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBufferedSeconds");
        assertThatThrownBy(() -> new DirectSttAudioAccountant(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remainingFramesReportsHeadroom() {
        DirectSttAudioAccountant a = accountant();
        reserve16k(a, SESSION, seconds16kMono(10));

        assertThat(a.remainingFrames(TENANT, SESSION, RATE_16K)).isEqualTo(20L * RATE_16K);
    }
}
