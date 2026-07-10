package com.example.audiogateway.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Versioned client-to-gateway audio frame used by the Faz 24 live WebSocket path.
 *
 * <p>Header fields are network byte order. The payload is signed little-endian
 * PCM16 and is converted to the float32 little-endian format expected by
 * live-stt without persistence.
 */
public record LiveAudioStreamFrame(
        int version,
        long chunkSeq,
        long capturedAtMs,
        byte[] pcm16
) {

    public static final int VERSION = 1;
    public static final int HEADER_BYTES = 19;

    public static LiveAudioStreamFrame decode(final byte[] bytes, final int maxPayloadBytes) {
        if (bytes == null || bytes.length < HEADER_BYTES) {
            throw new IllegalArgumentException("live audio frame is shorter than its header");
        }
        final ByteBuffer frame = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        final int version = Byte.toUnsignedInt(frame.get());
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported live audio frame version");
        }
        final long chunkSeq = frame.getLong();
        final long capturedAtMs = frame.getLong();
        final int payloadLength = Short.toUnsignedInt(frame.getShort());

        if (chunkSeq < 0 || capturedAtMs < 0) {
            throw new IllegalArgumentException("live audio frame sequence and timestamp must be non-negative");
        }
        if (payloadLength <= 0 || payloadLength > maxPayloadBytes) {
            throw new IllegalArgumentException("live audio frame payload length is outside the configured bound");
        }
        if ((payloadLength & 1) != 0) {
            throw new IllegalArgumentException("PCM16 payload length must be even");
        }
        if (frame.remaining() != payloadLength) {
            throw new IllegalArgumentException("live audio frame declared length does not match payload");
        }
        final byte[] pcm16 = new byte[payloadLength];
        frame.get(pcm16);
        return new LiveAudioStreamFrame(version, chunkSeq, capturedAtMs, pcm16);
    }

    /**
     * Convert signed little-endian PCM16 samples to little-endian float32.
     */
    public byte[] toFloat32LittleEndian() {
        final ByteBuffer input = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer output = ByteBuffer
                .allocate((pcm16.length / Short.BYTES) * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        while (input.hasRemaining()) {
            output.putFloat(input.getShort() / 32768.0f);
        }
        return output.array();
    }

    public int durationMs(final int sampleRateHz, final int channels) {
        final int sampleCount = pcm16.length / (Short.BYTES * channels);
        return (int) Math.round(sampleCount * 1000.0d / sampleRateHz);
    }
}
