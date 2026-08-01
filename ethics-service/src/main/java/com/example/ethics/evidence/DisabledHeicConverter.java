package com.example.ethics.evidence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Fail-closed default: without a configured converter, HEIC is refused, never guessed. */
@Component
@ConditionalOnProperty(name = "ethics.evidence.processor.heic-converter-url",
        havingValue = "", matchIfMissing = true)
public class DisabledHeicConverter implements HeicConverter {
    @Override
    public byte[] toPng(byte[] heic) {
        throw new EvidenceProcessor.ProcessingException(
                EvidenceProcessor.ProcessingException.Outcome.POLICY,
                "EVIDENCE_HEIC_CONVERTER_NOT_CONFIGURED");
    }
}
