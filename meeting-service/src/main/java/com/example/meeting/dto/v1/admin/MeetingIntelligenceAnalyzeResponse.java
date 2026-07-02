package com.example.meeting.dto.v1.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeetingIntelligenceAnalyzeResponse(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("grounding_policy") String groundingPolicy,
        String summary,
        @JsonProperty("summary_grounding_status") String summaryGroundingStatus,
        @JsonProperty("summary_citations") List<MeetingIntelligenceCitation> summaryCitations,
        List<String> decisions,
        @JsonProperty("action_items") List<MeetingIntelligenceActionItem> actionItems,
        List<MeetingIntelligenceCitation> citations,
        @JsonProperty("rejected_claims") List<MeetingIntelligenceRejectedClaim> rejectedClaims,
        @JsonProperty("ungrounded_count") Integer ungroundedCount,
        Boolean redacted,
        @JsonProperty("redaction_count") Integer redactionCount,
        String backend,
        String model,
        @JsonProperty("elapsed_ms") Integer elapsedMs,
        UUID meetingId,
        String sessionId,
        Boolean persisted,
        String storageMode
) {

    public MeetingIntelligenceAnalyzeResponse {
        summary = summary == null ? "" : summary;
        summaryCitations = safeList(summaryCitations);
        decisions = safeList(decisions);
        actionItems = safeList(actionItems);
        citations = safeList(citations);
        rejectedClaims = safeList(rejectedClaims);
    }

    public MeetingIntelligenceAnalyzeResponse withMeetingEnvelope(UUID resolvedMeetingId, String resolvedSessionId) {
        return new MeetingIntelligenceAnalyzeResponse(
                schemaVersion,
                groundingPolicy,
                summary,
                summaryGroundingStatus,
                summaryCitations,
                decisions,
                actionItems,
                citations,
                rejectedClaims,
                ungroundedCount,
                redacted,
                redactionCount,
                backend,
                model,
                elapsedMs,
                resolvedMeetingId,
                resolvedSessionId,
                false,
                "preview"
        );
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
