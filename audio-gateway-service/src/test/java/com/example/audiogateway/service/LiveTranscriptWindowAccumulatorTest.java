package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class LiveTranscriptWindowAccumulatorTest {

    @Test
    void materializesExactPartialFrameRangesAndAllowsTailOverlap() {
        final LiveTranscriptWindowAccumulator accumulator =
                new LiveTranscriptWindowAccumulator(24);
        accumulator.append(frame(0, 1_000L, 1, 2, 3, 4), 1_000, 1);
        accumulator.append(frame(1, 1_004L, 5, 6, 7, 8), 1_000, 1);
        accumulator.append(frame(2, 1_008L, 9, 10, 11, 12), 1_000, 1);

        final LiveTranscriptWindowAccumulator.Window first = accumulator.take(0, 1, 7);
        final LiveTranscriptWindowAccumulator.Window second = accumulator.take(1, 6, 11);

        assertThat(first.firstChunkSeq()).isZero();
        assertThat(first.lastChunkSeq()).isEqualTo(1L);
        assertThat(first.startedAtMs()).isEqualTo(1_001L);
        assertThat(first.endedAtMs()).isEqualTo(1_007L);
        assertThat(first.durationMs()).isEqualTo(6);
        assertThat(first.byteLength()).isEqualTo(12);
        assertThat(first.sha256()).isEqualTo(sha256(2, 3, 4, 5, 6, 7));

        assertThat(second.firstChunkSeq()).isEqualTo(1L);
        assertThat(second.lastChunkSeq()).isEqualTo(2L);
        assertThat(second.startedAtMs()).isEqualTo(1_006L);
        assertThat(second.endedAtMs()).isEqualTo(1_011L);
        assertThat(second.durationMs()).isEqualTo(5);
        assertThat(second.byteLength()).isEqualTo(10);
        assertThat(second.sha256()).isEqualTo(sha256(7, 8, 9, 10, 11));
    }

    @Test
    void failsClosedWhenFinalRangeHasFallenOutOfBoundedHistory() {
        final LiveTranscriptWindowAccumulator accumulator =
                new LiveTranscriptWindowAccumulator(8);
        accumulator.append(frame(0, 1_000L, 1, 2, 3, 4), 1_000, 1);
        accumulator.append(frame(1, 1_004L, 5, 6, 7, 8), 1_000, 1);

        assertThatThrownBy(() -> accumulator.take(0, 0, 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeded bounded history");

        assertThat(accumulator.take(0, 4, 8).sha256()).isEqualTo(sha256(5, 6, 7, 8));
    }

    private static LiveAudioStreamFrame frame(
            final long chunkSeq, final long capturedAtMs, final int... samples) {
        return new LiveAudioStreamFrame(
                LiveAudioStreamFrame.VERSION,
                chunkSeq,
                capturedAtMs,
                pcm16(samples));
    }

    private static String sha256(final int... samples) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(pcm16(samples)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] pcm16(final int... samples) {
        final ByteBuffer buffer = ByteBuffer.allocate(samples.length * Short.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int sample : samples) {
            buffer.putShort((short) sample);
        }
        return buffer.array();
    }
}
