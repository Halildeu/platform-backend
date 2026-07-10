package com.example.meeting.dto.v1.internal;

import com.example.meeting.dto.v1.admin.MeetingIntelligenceActionItem;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceCitation;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceRejectedClaim;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * meeting-ai-service -> meeting-service single atomic aggregate-ingestion
 * payload — #244 BE-1, acceptance condition 1.
 *
 * <p>{@code meetingId} here is a defense-in-depth cross-check against the
 * path variable (mirrors the existing {@code MeetingIntelligenceAnalyzeRequest}
 * convention) — org/tenant binding is deliberately NOT part of this payload
 * (acceptance condition 4): the service resolves it from the {@code meetingId}
 * path variable via {@code MeetingRepository}, never from a caller-supplied
 * claim, so a compromised or buggy caller cannot write into another tenant's
 * meeting by lying about scope in the body.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MeetingAnalysisResultIngestionRequest(
        UUID meetingId,
        @JsonProperty("analysis_run_id") @NotBlank String analysisRunId,
        @JsonProperty("transcript_id") @NotBlank String transcriptId,
        @JsonProperty("transcript_revision") @NotBlank String transcriptRevision,
        @JsonProperty("analyzer_contract_version") @NotBlank String analyzerContractVersion,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("prompt_version") String promptVersion,
        @NotBlank String summary,
        List<String> decisions,
        @JsonProperty("actions") List<@Valid MeetingIntelligenceActionItem> actions,
        List<@Valid MeetingIntelligenceCitation> citations,
        @JsonProperty("rejected_claims") List<@Valid MeetingIntelligenceRejectedClaim> rejectedClaims,
        @JsonProperty("generated_at") @NotNull Instant generatedAt
) {

    public MeetingAnalysisResultIngestionRequest {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        actions = actions == null ? List.of() : List.copyOf(actions);
        citations = citations == null ? List.of() : List.copyOf(citations);
        rejectedClaims = rejectedClaims == null ? List.of() : List.copyOf(rejectedClaims);
    }
}
