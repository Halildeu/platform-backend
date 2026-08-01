package com.example.ethics.evidence;

import com.example.ethics.config.EvidenceProperties;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * TEST reference sanitizer for plain UTF-8 text, JPEG and PNG. It uses
 * signature/structural parsing rather than extension or client MIME and
 * re-encodes images into a new metadata-free raster.
 *
 * <p>PDF is intentionally rejected here. The accepted contract requires PDF
 * rendering in a dedicated no-network CDR sandbox; processing it in the public
 * API JVM would create a false security claim.
 */
@Component
@ConditionalOnProperty(
        name = "ethics.evidence.processor.mode",
        havingValue = "clamav-reference")
public class SafeMetadataSanitizer {
    private final EvidenceProperties properties;

    public SafeMetadataSanitizer(EvidenceProperties properties) {
        this.properties = properties;
    }

    public Sanitized sanitize(byte[] source, String declaredMediaType) {
        String actual = detect(source);
        String declared = normalizeDeclared(declaredMediaType);
        if (!actual.equals(declared)) {
            throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.INTEGRITY,
                    "EVIDENCE_MEDIA_SIGNATURE_MISMATCH");
        }
        return switch (actual) {
            case "text/plain; charset=utf-8" -> sanitizeText(source);
            case "image/jpeg" -> sanitizeImage(source, "jpeg", actual);
            case "image/png" -> sanitizeImage(source, "png", actual);
            // ES-104K: the derivative is PNG, not WebP — flattening removes every
            // container chunk (EXIF, GPS, ICC, XMP) BY CONSTRUCTION rather than by
            // enumeration, and the case handler needs no special viewer.
            case "image/webp" -> sanitizeImage(source, "png", "image/png");
            case "application/pdf" -> throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.POLICY,
                    "EVIDENCE_PDF_CDR_NOT_CONFIGURED");
            default -> throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.POLICY,
                    "EVIDENCE_MEDIA_POLICY_DENIED");
        };
    }

    static String detect(byte[] source) {
        if (source.length >= 5
                && source[0] == '%' && source[1] == 'P' && source[2] == 'D'
                && source[3] == 'F' && source[4] == '-') {
            return "application/pdf";
        }
        if (source.length >= 8
                && (source[0] & 0xff) == 0x89 && source[1] == 'P'
                && source[2] == 'N' && source[3] == 'G'
                && (source[4] & 0xff) == 0x0d && (source[5] & 0xff) == 0x0a
                && (source[6] & 0xff) == 0x1a && (source[7] & 0xff) == 0x0a) {
            return "image/png";
        }
        if (source.length >= 3
                && (source[0] & 0xff) == 0xff
                && (source[1] & 0xff) == 0xd8
                && (source[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        // ES-104K (#2930): RIFF container carrying a WEBP fourcc. Byte 4-7 is the
        // chunk size — deliberately ignored; the fourcc at 8-11 is the identity.
        if (source.length >= 12
                && source[0] == 'R' && source[1] == 'I' && source[2] == 'F' && source[3] == 'F'
                && source[8] == 'W' && source[9] == 'E' && source[10] == 'B' && source[11] == 'P') {
            return "image/webp";
        }
        if (isDeniedBinarySignature(source)) {
            throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.POLICY,
                    "EVIDENCE_ACTIVE_OR_CONTAINER_CONTENT_DENIED");
        }
        decodeUtf8(source);
        return "text/plain; charset=utf-8";
    }

    private Sanitized sanitizeText(byte[] source) {
        String value = decodeUtf8(source);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if ((character < 0x20 && character != '\n' && character != '\r' && character != '\t')
                    || character == 0x7f) {
                throw new EvidenceProcessor.ProcessingException(
                        EvidenceProcessor.ProcessingException.Outcome.POLICY,
                        "EVIDENCE_TEXT_CONTROL_CHARACTER_DENIED");
            }
        }
        String normalized = Normalizer.normalize(
                value.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFC);
        return new Sanitized(
                normalized.getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=utf-8");
    }

    private Sanitized sanitizeImage(byte[] source, String format, String mediaType) {
        try {
            // ES-104K hardening, applied to every raster format: the declared canvas is
            // read from the HEADER and refused before any pixel is decoded. The old
            // order (decode, then count pixels) still let a small file spend the
            // worker's memory on the decode itself — the exact #2860 failure shape.
            requireDeclaredCanvasWithinBudget(source);
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
            if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
                throw new EvidenceProcessor.ProcessingException(
                        EvidenceProcessor.ProcessingException.Outcome.INTEGRITY,
                        "EVIDENCE_IMAGE_PARSE_FAILED");
            }
            long pixels = Math.multiplyExact((long) decoded.getWidth(), (long) decoded.getHeight());
            if (pixels > properties.getProcessor().getMaxDecodedImagePixels()) {
                throw new EvidenceProcessor.ProcessingException(
                        EvidenceProcessor.ProcessingException.Outcome.POLICY,
                        "EVIDENCE_IMAGE_DECOMPRESSION_LIMIT");
            }
            BufferedImage clean;
            if ("jpeg".equals(format)) {
                clean = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = clean.createGraphics();
                graphics.drawImage(decoded, 0, 0, null);
                graphics.dispose();
            } else {
                clean = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = clean.createGraphics();
                graphics.drawImage(decoded, 0, 0, null);
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(clean, format, output)) {
                throw new IllegalStateException("No safe image writer");
            }
            return new Sanitized(output.toByteArray(), mediaType);
        } catch (EvidenceProcessor.ProcessingException error) {
            throw error;
        } catch (Exception error) {
            throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.SANITIZE_FAILED,
                    "EVIDENCE_IMAGE_SANITIZE_FAILED",
                    error);
        }
    }

    /** Header-only dimension read; the reader never hands out pixels here. */
    private void requireDeclaredCanvasWithinBudget(byte[] source) throws java.io.IOException {
        try (javax.imageio.stream.ImageInputStream input =
                ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new EvidenceProcessor.ProcessingException(
                        EvidenceProcessor.ProcessingException.Outcome.INTEGRITY,
                        "EVIDENCE_IMAGE_PARSE_FAILED");
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                if (width <= 0 || height <= 0
                        || width * height > properties.getProcessor().getMaxDecodedImagePixels()) {
                    throw new EvidenceProcessor.ProcessingException(
                            EvidenceProcessor.ProcessingException.Outcome.POLICY,
                            "EVIDENCE_IMAGE_DECOMPRESSION_LIMIT");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static String normalizeDeclared(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if ("text/plain".equals(normalized)) return "text/plain; charset=utf-8";
        return normalized;
    }

    private static String decodeUtf8(byte[] source) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new EvidenceProcessor.ProcessingException(
                    EvidenceProcessor.ProcessingException.Outcome.INTEGRITY,
                    "EVIDENCE_UTF8_INVALID");
        }
    }

    private static boolean isDeniedBinarySignature(byte[] source) {
        return startsWith(source, new byte[]{'P', 'K', 0x03, 0x04})
                || startsWith(source, new byte[]{0x1f, (byte) 0x8b})
                || startsWith(source, new byte[]{'R', 'a', 'r', '!'})
                || startsWith(source, new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf})
                || startsWith(source, new byte[]{'M', 'Z'})
                || startsWith(source, new byte[]{0x7f, 'E', 'L', 'F'})
                || startsWithAsciiIgnoreCase(source, "<!doctype html")
                || startsWithAsciiIgnoreCase(source, "<html")
                || startsWithAsciiIgnoreCase(source, "<svg")
                || startsWith(source, new byte[]{'#', '!'});
    }

    private static boolean startsWith(byte[] source, byte[] prefix) {
        if (source.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (source[i] != prefix[i]) return false;
        }
        return true;
    }

    private static boolean startsWithAsciiIgnoreCase(byte[] source, String prefix) {
        if (source.length < prefix.length()) return false;
        String head = new String(source, 0, prefix.length(), StandardCharsets.US_ASCII);
        return head.equalsIgnoreCase(prefix);
    }

    public record Sanitized(byte[] content, String mediaType) {}
}
