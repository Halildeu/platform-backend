package com.example.ethics.evidence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Fail-closed default: without a configured converter, HEIC is refused, never guessed.
 *
 * <p>The condition is the exact NEGATION of {@link HttpHeicConverter}'s, written as the
 * same expression — because the first deployment of this pair crash-looped on a subtle
 * {@code @ConditionalOnProperty} semantic: {@code havingValue = ""} does not mean "the
 * property is empty", it means "any value but false", so with a URL configured BOTH beans
 * existed and the context died on the ambiguity. Two conditions that must be mutually
 * exclusive get one shared expression, negated — never two vocabularies.
 */
@Component
@ConditionalOnExpression(
        "'${ethics.evidence.processor.heic-converter-url:}'.isEmpty()")
public class DisabledHeicConverter implements HeicConverter {
    @Override
    public byte[] toPng(byte[] heic) {
        throw new EvidenceProcessor.ProcessingException(
                EvidenceProcessor.ProcessingException.Outcome.POLICY,
                "EVIDENCE_HEIC_CONVERTER_NOT_CONFIGURED");
    }
}
