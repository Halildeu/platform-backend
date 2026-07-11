package com.example.meeting.dto.v1.internal;

import com.example.meeting.dto.v1.admin.MeetingIntelligenceCitation;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceRejectedClaim;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * INTERNAL ingestion payload for a completed meeting-ai analysis result —
 * Faz 24 (platform-ai#244 BE-1c). Sent by meeting-ai-service (the single
 * server-side PRODUCER) to meeting-service (the single SYSTEM OF RECORD) via
 * {@code POST /api/v1/internal/meetings/{meetingId}/analysis-results} with the
 * {@code Idempotency-Key: <analysisRunId UUID>} header.
 *
 * <p><b>Tenant is NEVER read from this body.</b> The org/tenant is resolved
 * canonically from the {@code {meetingId}} path row; a payload cannot smuggle a
 * foreign tenant/org (they are not fields here). The optional {@link #meetingId}
 * is a defence-in-depth cross-check against the path only.
 *
 * <p><b>Null collections are normalised to empty</b> in the compact constructor
 * so {@code "actions": null} and {@code "actions": []} are the same request for
 * both the idempotency hash and persistence. Element-level constraints are kept
 * (no defensive copy) so a null/blank element yields a clean 400 rather than an
 * NPE.
 *
 * <p>Field size bounds mirror the DB columns (see {@code V1}/{@code V3}); list
 * bounds cap the per-request blast radius (a body-level DoS/mistake guard that
 * complements the servlet/gateway byte limits).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MeetingAnalysisResultIngestRequest(

        /** Optional cross-check ONLY; the authoritative meetingId is the path variable. */
        @JsonAlias("meetingId")
        @JsonProperty("meeting_id")
        UUID meetingId,

        @JsonAlias("transcriptSessionId")
        @JsonProperty("transcript_session_id")
        @NotBlank
        @Size(max = 64)
        String transcriptSessionId,

        /** Lowercase SHA-256 hex — matches the DB CHECK. Identifies the snapshot, does not order it. */
        @JsonAlias("transcriptSha256")
        @JsonProperty("transcript_sha256")
        @NotBlank
        @Pattern(regexp = "^[0-9a-f]{64}$", message = "must be lowercase SHA-256 hex (64 chars)")
        String transcriptSha256,

        @JsonAlias("analyzerContractVersion")
        @JsonProperty("analyzer_contract_version")
        @NotBlank
        @Size(max = 64)
        String analyzerContractVersion,

        @Size(max = 128)
        String model,

        @Size(max = 64)
        String backend,

        @JsonAlias("promptVersion")
        @JsonProperty("prompt_version")
        @Size(max = 64)
        String promptVersion,

        @Size(max = 200_000)
        String summary,

        @JsonAlias("summaryGroundingStatus")
        @JsonProperty("summary_grounding_status")
        @Size(max = 32)
        String summaryGroundingStatus,

        @JsonAlias("summaryCitations")
        @JsonProperty("summary_citations")
        @Size(max = MAX_CITATIONS)
        List<@NotNull @Valid MeetingIntelligenceCitation> summaryCitations,

        @Size(max = MAX_CITATIONS)
        List<@NotNull @Valid MeetingIntelligenceCitation> citations,

        @JsonAlias("rejectedClaims")
        @JsonProperty("rejected_claims")
        @Size(max = MAX_CITATIONS)
        List<@NotNull @Valid MeetingIntelligenceRejectedClaim> rejectedClaims,

        @JsonAlias("ungroundedCount")
        @JsonProperty("ungrounded_count")
        @PositiveOrZero
        Integer ungroundedCount,

        Boolean redacted,

        @JsonAlias("redactionCount")
        @JsonProperty("redaction_count")
        @PositiveOrZero
        Integer redactionCount,

        /** Producer-stamped analysis timestamp; becomes the run's generated_at and each AI decision's decided_at. */
        @JsonAlias("generatedAt")
        @JsonProperty("generated_at")
        @NotNull
        Instant generatedAt,

        /** meeting-ai returns decisions as plain strings; each maps to one AI-provenance meeting_decisions row. */
        @Size(max = MAX_DECISIONS)
        List<@NotBlank @Size(max = 4000) String> decisions,

        @Size(max = MAX_ACTIONS)
        List<@NotNull @Valid MeetingAnalysisActionIngest> actions,

        /** Optional explicit supersession link; must reference an existing run in the SAME tenant+meeting. */
        @JsonAlias("supersedesAnalysisRunId")
        @JsonProperty("supersedes_analysis_run_id")
        UUID supersedesAnalysisRunId
) {

    /** Per-request blast-radius caps (body-level DoS/mistake guard). */
    public static final int MAX_CITATIONS = 2000;
    public static final int MAX_DECISIONS = 1000;
    public static final int MAX_ACTIONS = 1000;

    public MeetingAnalysisResultIngestRequest {
        // Normalise null collections to empty so null ≡ [] for hashing + mapping.
        // Deliberately NOT a defensive copy: element constraints (@NotBlank /
        // @NotNull) must still fire on a null element to produce a clean 400.
        summaryCitations = summaryCitations == null ? List.of() : summaryCitations;
        citations = citations == null ? List.of() : citations;
        rejectedClaims = rejectedClaims == null ? List.of() : rejectedClaims;
        decisions = decisions == null ? List.of() : decisions;
        actions = actions == null ? List.of() : actions;
    }
}
