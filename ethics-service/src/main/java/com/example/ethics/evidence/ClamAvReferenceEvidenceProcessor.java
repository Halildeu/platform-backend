package com.example.ethics.evidence;

import com.example.ethics.config.EvidenceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "ethics.evidence.processor.mode",
        havingValue = "clamav-reference")
public class ClamAvReferenceEvidenceProcessor implements EvidenceProcessor {
    private final ClamAvScanner scanner;
    private final SafeMetadataSanitizer sanitizer;
    private final EvidenceProperties properties;

    public ClamAvReferenceEvidenceProcessor(
            ClamAvScanner scanner,
            SafeMetadataSanitizer sanitizer,
            EvidenceProperties properties) {
        this.scanner = scanner;
        this.sanitizer = sanitizer;
        this.properties = properties;
    }

    @Override
    public ProcessedEvidence process(byte[] original, String declaredMediaType) {
        requireImmutableToolEvidence();
        String detected = SafeMetadataSanitizer.detect(original);
        ClamAvScanner.ScanResult originalScan = scanner.scan(original);
        assertClean(originalScan.verdict());
        requireExpectedRules(originalScan.rulesVersion());
        SafeMetadataSanitizer.Sanitized clean = sanitizer.sanitize(original, declaredMediaType);
        ClamAvScanner.ScanResult derivativeScan = scanner.scan(clean.content());
        assertClean(derivativeScan.verdict());
        requireExpectedRules(derivativeScan.rulesVersion());
        if (!originalScan.rulesVersion().equals(derivativeScan.rulesVersion())) {
            throw new ProcessingException(
                    ProcessingException.Outcome.UNAVAILABLE,
                    "EVIDENCE_SCANNER_RULES_CHANGED_DURING_JOB");
        }
        EvidenceProperties.Processor config = properties.getProcessor();
        return new ProcessedEvidence(
                clean.content(),
                detected,
                clean.mediaType(),
                config.getScannerDigest(),
                config.getSanitizerDigest(),
                config.getParserDigest(),
                originalScan.rulesVersion(),
                config.getTransformationProfile());
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

    private static boolean isDigest(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
}
