package com.example.audiogateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * live-stt {@code /transcribe} response shape — Faz 24 issue #182 direct-STT path.
 *
 * <p>Mirrors the PROVEN live-stt response:
 * {@code {text, language, language_probability, duration, elapsed_ms, model,
 * compute_type, device, segments}}.
 *
 * <p><b>PII boundary (ADR-0030):</b> {@link #text} and {@link #segments} carry the
 * transcript content. They are parsed for downstream routing (issue #182 follow-up
 * seam) but MUST NEVER be logged or echoed. Only the non-content metadata
 * ({@link #language}, {@link #durationSeconds}, {@link #elapsedMs}, {@link #model},
 * {@link #computeType}, {@link #device}) is PII-safe to log.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps the contract forward-
 * compatible if live-stt adds fields. {@code segments} is left as a raw JSON node so
 * this seam does not over-commit to the segment schema (the transcript assembler in the
 * follow-up owns that).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TranscriptResult(
        String text,
        String language,
        @JsonProperty("language_probability") Double languageProbability,
        @JsonProperty("duration") Double durationSeconds,
        @JsonProperty("elapsed_ms") Double elapsedMs,
        String model,
        @JsonProperty("compute_type") String computeType,
        String device,
        com.fasterxml.jackson.databind.JsonNode segments) {

    /** Character count of the transcript text — PII-safe size signal (NOT the text itself). */
    public int textLength() {
        return text == null ? 0 : text.length();
    }
}
