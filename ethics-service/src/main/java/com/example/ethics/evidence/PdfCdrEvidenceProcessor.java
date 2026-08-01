package com.example.ethics.evidence;

import com.example.ethics.config.EvidenceProperties;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ES-104J (#2929) — the PDF lane's processor: scan, disarm-by-reconstruction, scan again.
 *
 * <p>Runs only in the dedicated CDR worker deployment ({@code pdf-cdr} mode), never in the
 * request-facing JVM — the same one-image-two-roles separation as the clamav-reference
 * worker, with its own memory budget so a hostile PDF exhausts <em>this</em> lane and
 * nothing else. Scan discipline is identical to the reference processor on purpose: the
 * original is scanned before any parsing (a known-malicious file should fail as MALICIOUS,
 * not as a parse error), and the rebuilt derivative is scanned again under the same pinned
 * rules, because a transformation that cannot re-pass the scanner has no business being
 * served.
 */
@Component
@ConditionalOnProperty(name = "ethics.evidence.processor.mode", havingValue = "pdf-cdr")
public class PdfCdrEvidenceProcessor implements EvidenceProcessor {

    private final ClamAvScanner scanner;
    private final PdfCdrRenderer renderer;
    private final EvidenceProperties properties;

    public PdfCdrEvidenceProcessor(
            ClamAvScanner scanner, PdfCdrRenderer renderer, EvidenceProperties properties) {
        this.scanner = scanner;
        this.renderer = renderer;
        this.properties = properties;
    }

    @Override
    public ProcessedEvidence process(byte[] original, String declaredMediaType) {
        requireImmutableToolEvidence();
        // Defence in depth behind the lane filter: this worker only ever answers for PDF.
        // A non-PDF row arriving here means the lane routing broke — refuse loudly rather
        // than degrade into a second, weaker image/text pipeline.
        String detected = SafeMetadataSanitizer.detect(original);
        if (!"application/pdf".equals(detected)) {
            throw new ProcessingException(
                    ProcessingException.Outcome.POLICY,
                    "EVIDENCE_CDR_LANE_MEDIA_MISMATCH");
        }
        if (!"application/pdf".equals(normalizeDeclared(declaredMediaType))) {
            throw new ProcessingException(
                    ProcessingException.Outcome.INTEGRITY,
                    "EVIDENCE_MEDIA_SIGNATURE_MISMATCH");
        }
        ClamAvScanner.ScanResult originalScan = scanner.scan(original);
        assertClean(originalScan.verdict());
        requireExpectedRules(originalScan.rulesVersion());
        PdfCdrRenderer.Flattened flattened = renderer.flatten(original);
        ClamAvScanner.ScanResult derivativeScan = scanner.scan(flattened.content());
        assertClean(derivativeScan.verdict());
        requireExpectedRules(derivativeScan.rulesVersion());
        if (!originalScan.rulesVersion().equals(derivativeScan.rulesVersion())) {
            throw new ProcessingException(
                    ProcessingException.Outcome.UNAVAILABLE,
                    "EVIDENCE_SCANNER_RULES_CHANGED_DURING_JOB");
        }
        EvidenceProperties.Processor config = properties.getProcessor();
        return new ProcessedEvidence(
                flattened.content(),
                detected,
                "application/pdf",
                config.getScannerDigest(),
                config.getSanitizerDigest(),
                config.getParserDigest(),
                originalScan.rulesVersion(),
                config.getTransformationProfile(),
                flattened.disarmed());
    }

    private static void assertClean(ClamAvScanner.Verdict verdict) {
        if (verdict == ClamAvScanner.Verdict.MALICIOUS) {
            throw new ProcessingException(
                    ProcessingException.Outcome.MALICIOUS,
                    "EVIDENCE_MALWARE_DETECTED");
        }
        if (verdict != ClamAvScanner.Verdict.CLEAN) {
            throw new ProcessingException(
                    ProcessingException.Outcome.UNAVAILABLE,
                    "EVIDENCE_SCANNER_UNKNOWN_VERDICT");
        }
    }

    private void requireImmutableToolEvidence() {
        EvidenceProperties.Processor config = properties.getProcessor();
        if (!isDigest(config.getScannerDigest())
                || !isDigest(config.getSanitizerDigest())
                || !isDigest(config.getParserDigest())
                || config.getRulesVersion() == null
                || config.getRulesVersion().isBlank()) {
            throw new ProcessingException(
                    ProcessingException.Outcome.UNAVAILABLE,
                    "EVIDENCE_TOOL_PROVENANCE_UNVERIFIED");
        }
    }

    private void requireExpectedRules(String observed) {
        if (!properties.getProcessor().getRulesVersion().equals(observed)) {
            throw new ProcessingException(
                    ProcessingException.Outcome.UNAVAILABLE,
                    "EVIDENCE_SCANNER_RULES_MISMATCH");
        }
    }

    private static String normalizeDeclared(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static boolean isDigest(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
}
