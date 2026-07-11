package com.example.meeting.dto.v1.internal;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * One action item inside an analysis-result ingestion payload — Faz 24
 * (platform-ai#244 BE-1c). This is the INTERNAL producer→system-of-record
 * contract (meeting-ai-service → meeting-service), distinct from the
 * {@code MeetingIntelligenceActionItem} preview DTO the desktop renderer sees.
 *
 * <p>Field mapping into {@code meeting_actions} (see the ingestion service):
 * {@code text → description}, {@code assignee → assignee_subject},
 * {@code due → due_at}. {@code due} is typed as an {@link Instant} (ISO-8601)
 * rather than free text: {@code due_at} is {@code TIMESTAMPTZ}, and the producer
 * is a server-side service we control, so the contract is type-safe end to end
 * with no lossy natural-language date parsing.
 *
 * <p>{@code @JsonAlias} accepts the preview DTO's {@code owner}/{@code due_date}
 * spellings too, so a producer that reuses the analyze-response shape still
 * binds.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MeetingAnalysisActionIngest(
        @NotBlank
        @Size(max = 2000)
        String text,
        @JsonAlias("owner")
        @Size(max = 255)
        String assignee,
        @JsonAlias("due_date")
        @JsonProperty("due")
        Instant due
) {
}
