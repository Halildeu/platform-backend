package com.example.meeting.dto.v1.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One canonical, persisted Meeting Intelligence snapshot for a meeting.
 * Every child in this response belongs to {@link #analysisRunId}; manual rows
 * and rows from other analysis runs are deliberately excluded.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeetingIntelligenceResultResponse(
        UUID analysisRunId,
        UUID meetingId,
        String sessionId,
        @JsonProperty("schema_version") String analyzerContractVersion,
        String model,
        String backend,
        String promptVersion,
        String summary,
        @JsonProperty("summary_grounding_status") String summaryGroundingStatus,
        @JsonProperty("summary_citations") List<MeetingIntelligenceCitation> summaryCitations,
        List<String> decisions,
        @JsonProperty("action_items") List<MeetingIntelligenceActionItem> actionItems,
        List<MeetingIntelligenceCitation> citations,
        @JsonProperty("rejected_claims") List<MeetingIntelligenceRejectedClaim> rejectedClaims,
        @JsonProperty("ungrounded_count") int ungroundedCount,
        boolean redacted,
        @JsonProperty("redaction_count") int redactionCount,
        Instant generatedAt,
        UUID supersedesAnalysisRunId,
        boolean persisted,
        String storageMode
) {
    public MeetingIntelligenceResultResponse {
        summary = summary == null ? "" : summary;
        summaryCitations = safeList(summaryCitations);
        decisions = safeList(decisions);
        actionItems = safeList(actionItems);
        citations = safeList(citations);
        rejectedClaims = safeList(rejectedClaims);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
