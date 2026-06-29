package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link WavEncoder} (Faz 24 issue #182) — proves the synthesized container is
 * a canonical PCM WAV that a generic decoder (mirroring live-stt) can parse, and that the PCM
 * payload is preserved byte-for-byte.
 */
class WavEncoderTest {

    private static String tag(final byte[] wav, final int offset) {
        return new String(wav, offset, 4, StandardCharsets.US_ASCII);
    }

    private static long le32(final byte[] wav, final int offset) {
        return ByteBuffer.wrap(wav, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFF_FFFFL;
    }

    private static int le16(final byte[] wav, final int offset) {
        return ByteBuffer.wrap(wav, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }

    @Test
    void wrapsPcm16WithCanonicalHeaderAndPreservesPayload() {
        final byte[] pcm = {10, 20, 30, 40, 50, 60, 70, 80}; // 4 samples, 16-bit mono
        final byte[] wav = WavEncoder.pcm16ToWav(pcm, 16_000, 1);

        assertThat(wav).hasSize(WavEncoder.HEADER_BYTES + pcm.length);
        assertThat(tag(wav, 0)).isEqualTo("RIFF");
        assertThat(le32(wav, 4)).as("ChunkSize = 36 + dataLen").isEqualTo(36 + pcm.length);
        assertThat(tag(wav, 8)).isEqualTo("WAVE");
        assertThat(tag(wav, 12)).isEqualTo("fmt ");
        assertThat(le32(wav, 16)).as("Subchunk1Size = 16 (PCM)").isEqualTo(16);
        assertThat(le16(wav, 20)).as("AudioFormat = 1 (PCM)").isEqualTo(1);
        assertThat(le16(wav, 22)).as("NumChannels").isEqualTo(1);
        assertThat(le32(wav, 24)).as("SampleRate").isEqualTo(16_000);
        assertThat(le32(wav, 28)).as("ByteRate = sr*ch*2").isEqualTo(16_000L * 1 * 2);
        assertThat(le16(wav, 32)).as("BlockAlign = ch*2").isEqualTo(2);
        assertThat(le16(wav, 34)).as("BitsPerSample").isEqualTo(16);
        assertThat(tag(wav, 36)).isEqualTo("data");
        assertThat(le32(wav, 40)).as("Subchunk2Size = dataLen").isEqualTo(pcm.length);
        assertThat(Arrays.copyOfRange(wav, 44, wav.length))
                .as("PCM payload copied verbatim after the header").isEqualTo(pcm);
    }

    @Test
    void encodesStereoAnd48kHzFieldsCorrectly() {
        final byte[] wav = WavEncoder.pcm16ToWav(new byte[16], 48_000, 2);
        assertThat(le16(wav, 22)).as("NumChannels").isEqualTo(2);
        assertThat(le32(wav, 24)).as("SampleRate").isEqualTo(48_000);
        assertThat(le32(wav, 28)).as("ByteRate = 48000*2*2").isEqualTo(48_000L * 2 * 2);
        assertThat(le16(wav, 32)).as("BlockAlign = 2*2").isEqualTo(4);
    }

    @Test
    void appliesSafeDefaultsForNonPositiveParams() {
        final byte[] wav = WavEncoder.pcm16ToWav(new byte[4], 0, 0);
        assertThat(le16(wav, 22)).as("channels clamps to 1").isEqualTo(1);
        assertThat(le32(wav, 24)).as("sampleRate defaults to 16000").isEqualTo(16_000);
    }

    @Test
    void handlesNullPayloadAsEmptyButValidWav() {
        final byte[] wav = WavEncoder.pcm16ToWav(null, 16_000, 1);
        assertThat(wav).hasSize(WavEncoder.HEADER_BYTES);
        assertThat(le32(wav, 40)).as("Subchunk2Size = 0 for empty payload").isZero();
    }

    @Test
    void producesAWavDecodableByJavaxSound() throws Exception {
        // Strongest proof the container is standard: a generic WAV decoder parses it and
        // reports the exact PCM format we encoded (mirrors what live-stt's decoder needs).
        final byte[] pcm = new byte[3200]; // 1600 frames @ 16-bit mono
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i % 251);
        }
        final byte[] wav = WavEncoder.pcm16ToWav(pcm, 16_000, 1);

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            final AudioFormat fmt = ais.getFormat();
            assertThat(fmt.getEncoding()).isEqualTo(AudioFormat.Encoding.PCM_SIGNED);
            assertThat(fmt.getSampleRate()).isEqualTo(16_000f);
            assertThat(fmt.getChannels()).isEqualTo(1);
            assertThat(fmt.getSampleSizeInBits()).isEqualTo(16);
            assertThat(ais.getFrameLength())
                    .as("frames = bytes / (channels * bytesPerSample)").isEqualTo(pcm.length / 2);
        }
    }
}
