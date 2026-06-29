package com.example.audiogateway.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal RIFF/WAVE (PCM) encoder — wraps raw little-endian PCM16 samples into a
 * self-describing WAV container.
 *
 * <p><b>Why (Faz 24 issue #182).</b> The recorder uploads headerless raw PCM16 chunks
 * ({@code X-Audio-Format: PCM16}). live-stt's {@code /transcribe} cannot decode headerless
 * PCM — proven live against the real service: raw PCM is rejected with HTTP 400 whether the
 * part is labelled {@code application/octet-stream} ("Unsupported content_type"),
 * {@code audio/L16}, or {@code audio/wav} ("Audio decode or inference failed"); only a real
 * WAV container sent as {@code audio/wav} returns 200 with a transcript. The gateway already
 * carries the session's {@code sampleRateHz} / {@code channels} (16-bit PCM) on the
 * {@link DirectSttForwardingDispatcher.ForwardTask}, so it synthesizes the canonical
 * 44-byte WAV header here and forwards the chunk as {@code audio/wav}.
 *
 * <p>The PCM payload is copied verbatim after the header — no resampling, no transcode; the
 * bytes that crossed the gateway are exactly the bytes inside the container.
 */
final class WavEncoder {

    /** Canonical PCM WAV header size (RIFF + fmt + data chunk headers). */
    static final int HEADER_BYTES = 44;

    private static final int PCM_SUBCHUNK1_SIZE = 16;
    private static final short AUDIO_FORMAT_PCM = 1;
    private static final short BITS_PER_SAMPLE = 16;
    private static final int BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8;
    private static final int DEFAULT_SAMPLE_RATE_HZ = 16_000;

    private WavEncoder() {
    }

    /**
     * Wrap raw little-endian PCM16 samples into a canonical 44-byte-header WAV container.
     *
     * @param pcm16        raw signed 16-bit little-endian PCM samples (interleaved when
     *                     multi-channel); {@code null} is treated as empty
     * @param sampleRateHz sample rate in Hz (falls back to 16000 when not positive)
     * @param channels     channel count (clamped to at least 1)
     * @return WAV bytes: 44-byte canonical header followed by the PCM payload unchanged
     */
    static byte[] pcm16ToWav(final byte[] pcm16, final int sampleRateHz, final int channels) {
        final byte[] data = pcm16 == null ? new byte[0] : pcm16;
        final int channelCount = Math.max(1, channels);
        final int rate = sampleRateHz > 0 ? sampleRateHz : DEFAULT_SAMPLE_RATE_HZ;
        final int dataLen = data.length;
        final int byteRate = rate * channelCount * BYTES_PER_SAMPLE;
        final short blockAlign = (short) (channelCount * BYTES_PER_SAMPLE);

        // Little-endian throughout per the WAV spec.
        final ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(ascii("RIFF"));
        buf.putInt(36 + dataLen);              // ChunkSize = 4 ("WAVE") + (8 + 16 fmt) + (8 + dataLen)
        buf.put(ascii("WAVE"));
        buf.put(ascii("fmt "));
        buf.putInt(PCM_SUBCHUNK1_SIZE);        // Subchunk1Size = 16 for PCM
        buf.putShort(AUDIO_FORMAT_PCM);        // AudioFormat = 1 (PCM, uncompressed)
        buf.putShort((short) channelCount);    // NumChannels
        buf.putInt(rate);                      // SampleRate
        buf.putInt(byteRate);                  // ByteRate = SampleRate * NumChannels * BytesPerSample
        buf.putShort(blockAlign);              // BlockAlign = NumChannels * BytesPerSample
        buf.putShort(BITS_PER_SAMPLE);         // BitsPerSample = 16
        buf.put(ascii("data"));
        buf.putInt(dataLen);                   // Subchunk2Size = number of PCM bytes
        buf.put(data);
        return buf.array();
    }

    private static byte[] ascii(final String tag) {
        return tag.getBytes(StandardCharsets.US_ASCII);
    }
}
