package com.example.meeting.dto.v1.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MeetingIntelligenceRejectedClaim(
        String claim,
        String kind,
        String status,
        String reason,
        Double similarity
) {
}
