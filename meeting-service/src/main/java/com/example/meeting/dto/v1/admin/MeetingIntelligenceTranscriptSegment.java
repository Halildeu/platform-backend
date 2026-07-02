package com.example.meeting.dto.v1.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MeetingIntelligenceTranscriptSegment(
        @NotBlank
        @Size(max = 20_000)
        String text,
        @DecimalMin("0.0")
        Double start,
        @DecimalMin("0.0")
        Double end
) {
}
