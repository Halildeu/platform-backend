package com.example.meeting.dto.v1.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection of a meeting's current CANONICAL analysis run + summary —
 * #244 BE-1b (the read-path counterpart to BE-1's write-only ingestion
 * endpoint; decisions/actions already had list endpoints, summary did not).
 *
 * <p>{@code decisions}/{@code action_items} are NOT duplicated here — they
 * are separately queryable via the existing {@code GET .../decisions} and
 * {@code GET .../actions} endpoints, filterable by {@code analysisRunId} if
 * a caller wants only this run's automated rows.
 */
public record MeetingAnalysisResultResponse(
        UUID meetingId,
        UUID analysisRunId,
        String status,
        String summary,
        String groundingStatus,
        String analyzerContractVersion,
        String modelVersion,
        String promptVersion,
        Instant generatedAt) {
}
