package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.audiogateway.service.AudioChunkDispatcher.DispatchOutcome;

import org.junit.jupiter.api.Test;

/**
 * #428 acceptance #1-#2 decision-core contract (Tracked by #428).
 *
 * <p>16 kHz mono PCM16 → 32000 bytes/second, so a 30-second bound is 960000 bytes.
 */
class PcmBacklogGateTest {

    private static final int SAMPLE_RATE_HZ = 16_000;
    private static final int CHANNELS = 1;
    private static final int BYTES_PER_SECOND = SAMPLE_RATE_HZ * CHANNELS * 2;
    private static final int MAX_BUFFERED_SECONDS = 30;
    private static final long LIMIT_BYTES = (long) MAX_BUFFERED_SECONDS * BYTES_PER_SECOND;
    private static final long RETRY_AFTER_SECONDS = 5L;

    private final PcmBacklogGate gate = new PcmBacklogGate(MAX_BUFFERED_SECONDS, RETRY_AFTER_SECONDS);

    @Test
    void emptyBacklogAdmits() {
        assertThat(gate.check(0L, BYTES_PER_SECOND, SAMPLE_RATE_HZ, CHANNELS)).isNull();
    }

    @Test
    void backlogLandingExactlyOnTheLimitIsAdmitted() {
        // Acceptance #2: at the limit the chunk is still accepted; only above it is 429.
        final int incoming = BYTES_PER_SECOND;
        assertThat(gate.check(LIMIT_BYTES - incoming, incoming, SAMPLE_RATE_HZ, CHANNELS)).isNull();
    }

    @Test
    void backlogCrossingTheLimitIsRejectedWithQueueFullAndRetryAfter() {
        final int incoming = BYTES_PER_SECOND;
        final DispatchOutcome outcome =
                gate.check(LIMIT_BYTES - incoming + 1, incoming, SAMPLE_RATE_HZ, CHANNELS);

        assertThat(outcome).isInstanceOf(DispatchOutcome.QueueFull.class);
        assertThat(((DispatchOutcome.QueueFull) outcome).retryAfterSeconds())
                .isEqualTo(RETRY_AFTER_SECONDS);
    }

    @Test
    void unknownPendingBytesSkipsTheGate() {
        // No consumer to decrement the counter yet: a monotonic counter would 429 forever.
        assertThat(gate.check(PcmBacklogGate.UNKNOWN_PENDING_BYTES, Integer.MAX_VALUE,
                SAMPLE_RATE_HZ, CHANNELS)).isNull();
    }

    @Test
    void boundScalesWithSampleRateAndChannels() {
        // Same byte backlog is half the duration at twice the bytes-per-second, so a
        // backlog that is rejected at 16 kHz mono passes at 16 kHz stereo.
        final long pending = LIMIT_BYTES;
        assertThat(gate.check(pending, 1, SAMPLE_RATE_HZ, CHANNELS))
                .isInstanceOf(DispatchOutcome.QueueFull.class);
        assertThat(gate.check(pending, 1, SAMPLE_RATE_HZ, 2)).isNull();
    }

    @Test
    void bufferedSecondsConvertsBytesToWholeSeconds() {
        assertThat(PcmBacklogGate.bufferedSeconds(0L, SAMPLE_RATE_HZ, CHANNELS)).isZero();
        assertThat(PcmBacklogGate.bufferedSeconds(BYTES_PER_SECOND, SAMPLE_RATE_HZ, CHANNELS))
                .isEqualTo(1L);
        assertThat(PcmBacklogGate.bufferedSeconds(LIMIT_BYTES, SAMPLE_RATE_HZ, CHANNELS))
                .isEqualTo(MAX_BUFFERED_SECONDS);
    }

    @Test
    void bufferedSecondsTreatsUnknownBacklogAsZero() {
        assertThat(PcmBacklogGate.bufferedSeconds(
                PcmBacklogGate.UNKNOWN_PENDING_BYTES, SAMPLE_RATE_HZ, CHANNELS)).isZero();
    }

    @Test
    void gateRejectsNonPositiveConfiguration() {
        assertThatThrownBy(() -> new PcmBacklogGate(0, RETRY_AFTER_SECONDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxBufferedSeconds must be positive");
        assertThatThrownBy(() -> new PcmBacklogGate(MAX_BUFFERED_SECONDS, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryAfterSeconds must be positive");
    }

    @Test
    void gateRejectsNegativeOrDegenerateInputs() {
        assertThatThrownBy(() -> gate.check(-2L, 1, SAMPLE_RATE_HZ, CHANNELS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pendingBytes must not be negative");
        assertThatThrownBy(() -> gate.check(0L, -1, SAMPLE_RATE_HZ, CHANNELS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomingBytes must not be negative");
        assertThatThrownBy(() -> gate.check(0L, 1, 0, CHANNELS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleRateHz must be positive");
        assertThatThrownBy(() -> gate.check(0L, 1, SAMPLE_RATE_HZ, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channels must be positive");
    }
}
