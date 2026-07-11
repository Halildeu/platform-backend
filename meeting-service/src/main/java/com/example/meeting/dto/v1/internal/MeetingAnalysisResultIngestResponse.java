package com.example.meeting.dto.v1.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Response for a successful analysis-result ingestion — Faz 24
 * (platform-ai#244 BE-1c). {@code persisted=true} + {@code storageMode="persisted"}
 * are the system-of-record counterparts to the analyze preview's
 * {@code persisted=false}/{@code storageMode="preview"}.
 *
 * <p>{@code idempotentReplay} distinguishes a retry that matched an existing run
 * (HTTP 200) from a fresh write (HTTP 201); the counts are the number of AI child
 * rows bound to the run (evidence the atomic write landed the children too).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeetingAnalysisResultIngestResponse(
        @JsonProperty("analysis_run_id") UUID analysisRunId,
        @JsonProperty("meeting_id") UUID meetingId,
        boolean persisted,
        @JsonProperty("storage_mode") String storageMode,
        @JsonProperty("idempotent_replay") boolean idempotentReplay,
        @JsonProperty("decision_count") int decisionCount,
        @JsonProperty("action_count") int actionCount,
        @JsonProperty("supersedes_analysis_run_id") UUID supersedesAnalysisRunId,
        @JsonProperty("generated_at") Instant generatedAt
) {

    public static MeetingAnalysisResultIngestResponse persisted(
            UUID analysisRunId,
            UUID meetingId,
            boolean idempotentReplay,
            int decisionCount,
            int actionCount,
            UUID supersedesAnalysisRunId,
            Instant generatedAt) {
        return new MeetingAnalysisResultIngestResponse(
                analysisRunId, meetingId, true, "persisted",
                idempotentReplay, decisionCount, actionCount,
                supersedesAnalysisRunId, generatedAt);
    }
}
