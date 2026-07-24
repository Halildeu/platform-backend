package com.example.ethics.evidence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "ethics.evidence.processor.mode",
        havingValue = "disabled",
        matchIfMissing = true)
public class DisabledEvidenceProcessor implements EvidenceProcessor {
    @Override
    public ProcessedEvidence process(byte[] original, String declaredMediaType) {
        throw new ProcessingException(
                ProcessingException.Outcome.UNAVAILABLE,
                "EVIDENCE_PROCESSOR_DISABLED");
    }
}
