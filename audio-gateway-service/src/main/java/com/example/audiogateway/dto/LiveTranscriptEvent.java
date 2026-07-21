package com.example.audiogateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.audiogateway.service.DirectSttTranscriptResultContext;
import java.util.List;

/**
 * What a live web viewer receives over SSE for one transcript increment (Faz 24 —
 * transcript readability).
 *
 * <p><b>Why this exists.</b> The broadcast used to publish a bare {@link TranscriptResult},
 * which says nothing about whether an increment is a raw acoustic chunk or the readable
 * line the gateway assembled from several of them. A viewer could only render both the
 * same way — showing the sentence twice, once in pieces (Codex {@code 019f869d} post-impl
 * REVISE point 5). Carrying the provenance lets the viewer render permanent lines from
 * {@code UTTERANCE} and treat {@code DRAFT} as volatile.
 *
 * <p><b>Backward compatible.</b> Every field a viewer already read keeps its name and
 * meaning; {@code status}, {@code assemblyReason} and {@code sourceEventIds} are additions,
 * and the latter two are omitted entirely for a raw chunk.
 *
 * <p>Carries transcript text — never raw audio, bearer data, or the chunk hash.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiveTranscriptEvent(
        String text,
        String language,
        @JsonProperty("language_probability") Double languageProbability,
        @JsonProperty("duration") Double durationSeconds,
        @JsonProperty("elapsed_ms") Double elapsedMs,
        String model,
        @JsonProperty("compute_type") String computeType,
        String device,
        /** {@code DRAFT} for a raw committed chunk, {@code UTTERANCE} for an assembled line. */
        String status,
        /** Why the assembled line closed; {@code null} for a raw chunk. */
        String assemblyReason,
        /** The chunks folded into an assembled line, in order; empty for a raw chunk. */
        List<String> sourceEventIds) {

    /** Status of a raw committed chunk, cut on an acoustic boundary. */
    public static final String STATUS_DRAFT = "DRAFT";

    /** Status of a line the gateway assembled from consecutive chunks. */
    public static final String STATUS_UTTERANCE = "UTTERANCE";

    public LiveTranscriptEvent {
        sourceEventIds = sourceEventIds == null ? List.of() : List.copyOf(sourceEventIds);
    }

    /** Build the viewer-facing event from a result and the context that carried it. */
    public static LiveTranscriptEvent of(
            final TranscriptResult result, final DirectSttTranscriptResultContext context) {
        final DirectSttTranscriptResultContext.Assembly assembly =
                context == null ? null : context.assembly();
        return new LiveTranscriptEvent(
                result == null ? null : result.text(),
                result == null ? null : result.language(),
                result == null ? null : result.languageProbability(),
                result == null ? null : result.durationSeconds(),
                result == null ? null : result.elapsedMs(),
                result == null ? null : result.model(),
                result == null ? null : result.computeType(),
                result == null ? null : result.device(),
                assembly == null ? STATUS_DRAFT : STATUS_UTTERANCE,
                assembly == null ? null : assembly.reason(),
                assembly == null ? List.of() : assembly.sourceEventIds());
    }
}
