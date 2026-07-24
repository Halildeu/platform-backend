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
}
