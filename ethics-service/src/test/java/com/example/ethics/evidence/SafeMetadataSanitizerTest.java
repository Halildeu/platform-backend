package com.example.ethics.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ethics.config.EvidenceProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SafeMetadataSanitizerTest {
    private final SafeMetadataSanitizer sanitizer =
            new SafeMetadataSanitizer(new EvidenceProperties());

    @Test
    void normalizesUtf8TextWithoutTreatingExtensionAsAuthority() {
        byte[] original = "satır-1\r\nsatır-2".getBytes(StandardCharsets.UTF_8);
        SafeMetadataSanitizer.Sanitized result =
                sanitizer.sanitize(original, "text/plain");
        assertThat(result.mediaType()).isEqualTo("text/plain; charset=utf-8");
        assertThat(new String(result.content(), StandardCharsets.UTF_8))
                .isEqualTo("satır-1\nsatır-2");
    }

    @Test
    void rejectsArchiveExecutableHtmlSvgAndScriptSignatures() {
        byte[][] denied = {
                {'P', 'K', 0x03, 0x04, 0x00},
                {'M', 'Z', 0x00, 0x00},
                "<html>active</html>".getBytes(StandardCharsets.US_ASCII),
                "<svg></svg>".getBytes(StandardCharsets.US_ASCII),
                "#!/bin/sh".getBytes(StandardCharsets.US_ASCII)
        };
        for (byte[] payload : denied) {
            assertThatThrownBy(() -> sanitizer.sanitize(payload, "text/plain"))
                    .isInstanceOf(EvidenceProcessor.ProcessingException.class)
                    .extracting(error -> ((EvidenceProcessor.ProcessingException) error).outcome())
                    .isEqualTo(EvidenceProcessor.ProcessingException.Outcome.POLICY);
        }
    }

    @Test
    void rejectsWrongMagicAndPdfWithoutDedicatedCdr() {
        assertThatThrownBy(() -> sanitizer.sanitize(
                "plain text".getBytes(StandardCharsets.UTF_8), "image/png"))
                .isInstanceOf(EvidenceProcessor.ProcessingException.class)
                .extracting(error -> ((EvidenceProcessor.ProcessingException) error).outcome())
                .isEqualTo(EvidenceProcessor.ProcessingException.Outcome.INTEGRITY);
        assertThatThrownBy(() -> sanitizer.sanitize(
                "%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII), "application/pdf"))
                .isInstanceOf(EvidenceProcessor.ProcessingException.class)
                .hasMessage("EVIDENCE_PDF_CDR_NOT_CONFIGURED");
    }

    @Test
    void reencodesPngIntoFreshRaster() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff55aa22);
        ByteArrayOutputStream source = new ByteArrayOutputStream();
        ImageIO.write(image, "png", source);
        SafeMetadataSanitizer.Sanitized result =
                sanitizer.sanitize(source.toByteArray(), "image/png");
        assertThat(result.mediaType()).isEqualTo("image/png");
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(result.content()));
        assertThat(decoded.getWidth()).isEqualTo(2);
        assertThat(decoded.getHeight()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // ES-104K (#2930) — WebP: the phone-photo formats stop bouncing.
    // Fixture provenance: encoded by Chrome 139 (canvas.toDataURL('image/webp'))
    // on 2026-08-01 — a REAL encoder's output, not a hand-crafted approximation,
    // and it carries an actual ICC metadata chunk ("Google Inc. 2016") which is
    // exactly what the derivative must not.
    // ------------------------------------------------------------------
    private static final byte[] CHROME_WEBP = java.util.Base64.getDecoder().decode(
            "UklGRkgCAABXRUJQVlA4WAoAAAAgAAAABwAABwAASUNDUMgBAAAAAAHIAAAAAAQwAABtbnRyUkdCIFhZWiAH4AABAAEAAAAAAABhY3Nw"
            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAA9tYAAQAAAADTLQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            + "AAAAAAAAAAAAAAAAAAlkZXNjAAAA8AAAACRyWFlaAAABFAAAABRnWFlaAAABKAAAABRiWFlaAAABPAAAABR3dHB0AAABUAAAABRyVFJD"
            + "AAABZAAAAChnVFJDAAABZAAAAChiVFJDAAABZAAAAChjcHJ0AAABjAAAADxtbHVjAAAAAAAAAAEAAAAMZW5VUwAAAAgAAAAcAHMAUgBH"
            + "AEJYWVogAAAAAAAAb6IAADj1AAADkFhZWiAAAAAAAABimQAAt4UAABjaWFlaIAAAAAAAACSgAAAPhAAAts9YWVogAAAAAAAA9tYAAQAA"
            + "AADTLXBhcmEAAAAAAAQAAAACZmYAAPKnAAANWQAAE9AAAApbAAAAAAAAAABtbHVjAAAAAAAAAAEAAAAMZW5VUwAAACAAAAAcAEcAbwBv"
            + "AGcAbABlACAASQBuAGMALgAgADIAMAAxADZWUDggWgAAADADAJ0BKggACAAAwBIlsAJ0ugH4AfgAiwD/AfwAGl3gVAAA/v/l47tM97aw"
            + "H//Ex/xGsEH/40svds4Z0EZ37cP/6wT/v//z//v/spc2aJbf4pP/FJ//iaAAAA==");

    @Test
    void webpIsDetectedBySignatureAndFlattenedToAMetadataFreePng() {
        assertThat(SafeMetadataSanitizer.detect(CHROME_WEBP)).isEqualTo("image/webp");
        SafeMetadataSanitizer.Sanitized clean = sanitizer.sanitize(CHROME_WEBP, "image/webp");
        // The derivative is PNG by construction — no WebP container chunk survives.
        assertThat(clean.mediaType()).isEqualTo("image/png");
        assertThat(clean.content()[0] & 0xff).isEqualTo(0x89);
        assertThat(new String(clean.content(), 1, 3,
                java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PNG");
        // The fixture's real ICC chunk ("Google Inc. 2016", UTF-16 in the source)
        // must be gone. Checked against the bytes, not a parser's opinion.
        String raw = new String(clean.content(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(raw).doesNotContain("ICCP").doesNotContain("G\u0000o\u0000o\u0000g");
    }

    @Test
    void aTruncatedWebpFailsClosed() {
        byte[] truncated = java.util.Arrays.copyOf(CHROME_WEBP, 40);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> sanitizer.sanitize(truncated, "image/webp"))
                .isInstanceOf(EvidenceProcessor.ProcessingException.class);
    }

    @Test
    void aDeclaredMismatchStillFailsForWebp() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> sanitizer.sanitize(CHROME_WEBP, "image/png"))
                .isInstanceOfSatisfying(
                        EvidenceProcessor.ProcessingException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("EVIDENCE_MEDIA_SIGNATURE_MISMATCH"));
    }

    /**
     * The canvas bomb dies BEFORE decode now: a 100-byte PNG whose header declares a
     * 50000x50000 canvas must be refused from the header alone. Crafted as raw bytes
     * because that is what an attacker sends; the IDAT is garbage on purpose — the
     * gate must fire before anything tries to inflate it.
     */
    @Test
    void aDeclaredCanvasBombIsRefusedFromTheHeaderBeforeDecoding() {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try {
            bytes.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
            java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
            byte[] ihdr = java.nio.ByteBuffer.allocate(17)
                    .put("IHDR".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    .putInt(50_000).putInt(50_000)
                    .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0)
                    .array();
            out.writeInt(13);
            out.write(ihdr);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(ihdr);
            out.writeInt((int) crc.getValue());
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> sanitizer.sanitize(bytes.toByteArray(), "image/png"))
                .isInstanceOfSatisfying(
                        EvidenceProcessor.ProcessingException.class,
                        error -> assertThat(error.code())
                                .isEqualTo("EVIDENCE_IMAGE_DECOMPRESSION_LIMIT"));
    }
}
