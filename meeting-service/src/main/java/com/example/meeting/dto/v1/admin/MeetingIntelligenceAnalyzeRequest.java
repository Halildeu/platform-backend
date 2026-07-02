package com.example.meeting.dto.v1.admin;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MeetingIntelligenceAnalyzeRequest(
        @JsonAlias("meetingId")
        @JsonProperty("meeting_id")
        UUID meetingId,
        @JsonAlias("sessionId")
        @JsonProperty("session_id")
        @Size(max = 64)
        String sessionId,
        @NotBlank
        @Size(max = 200_000)
        String transcript,
        @JsonAlias("sourcePackageVersion")
        @JsonProperty("source_package_version")
        @Size(max = 64)
        String sourcePackageVersion,
        @Valid
        List<MeetingIntelligenceTranscriptSegment> segments
) {
}
