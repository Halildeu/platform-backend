package com.example.meeting.dto.v1.internal;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/** Result of a {@code POST /api/v1/internal/meetings/{meetingId}/analysis-results} call — #244 BE-1. */
public record MeetingAnalysisResultIngestionResponse(
        @JsonProperty("analysis_run_id") String analysisRunId,
        @JsonProperty("meeting_id") UUID meetingId,
        @JsonProperty("replayed") boolean replayed
) {
}
