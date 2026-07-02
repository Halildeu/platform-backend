package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class LiveAudioStreamFrameTest {

    @Test
    void decodesVersionedFrameAndConvertsPcm16ToFloat32() {
        final byte[] pcm16 = ByteBuffer.allocate(6)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(Short.MIN_VALUE)
                .putShort((short) 0)
                .putShort(Short.MAX_VALUE)
                .array();

        final LiveAudioStreamFrame frame =
                LiveAudioStreamFrame.decode(encoded(7L, 1_000L, pcm16), 64);

        assertThat(frame.version()).isEqualTo(1);
        assertThat(frame.chunkSeq()).isEqualTo(7L);
        assertThat(frame.capturedAtMs()).isEqualTo(1_000L);
        assertThat(frame.pcm16()).containsExactly(pcm16);
        assertThat(frame.durationMs(1_000, 1)).isEqualTo(3);

        final ByteBuffer floats = ByteBuffer.wrap(frame.toFloat32LittleEndian())
                .order(ByteOrder.LITTLE_ENDIAN);
        assertThat(floats.getFloat()).isEqualTo(-1.0f);
        assertThat(floats.getFloat()).isEqualTo(0.0f);
        assertThat(floats.getFloat()).isCloseTo(0.9999695f, within(0.000001f));
    }

    @Test
    void rejectsUnsupportedVersionAndLengthMismatch() {
        final byte[] valid = encoded(0L, 0L, new byte[]{0, 0});
        valid[0] = 2;
        assertThatThrownBy(() -> LiveAudioStreamFrame.decode(valid, 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");

        final byte[] mismatch = encoded(0L, 0L, new byte[]{0, 0});
        mismatch[18] = 4;
        assertThatThrownBy(() -> LiveAudioStreamFrame.decode(mismatch, 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declared length");
    }

    @Test
    void rejectsOddOrOversizedPcmPayload() {
        assertThatThrownBy(() ->
                LiveAudioStreamFrame.decode(encoded(0L, 0L, new byte[]{1}), 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("even");

        assertThatThrownBy(() ->
                LiveAudioStreamFrame.decode(encoded(0L, 0L, new byte[66]), 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bound");
    }

    private static byte[] encoded(
            final long sequence,
            final long capturedAtMs,
            final byte[] pcm16) {
        return ByteBuffer.allocate(LiveAudioStreamFrame.HEADER_BYTES + pcm16.length)
                .order(ByteOrder.BIG_ENDIAN)
                .put((byte) LiveAudioStreamFrame.VERSION)
                .putLong(sequence)
                .putLong(capturedAtMs)
                .putShort((short) pcm16.length)
                .put(pcm16)
                .array();
    }

    private static org.assertj.core.data.Offset<Float> within(final float value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
