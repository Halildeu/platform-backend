package com.example.meeting.dto.v1.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MeetingIntelligenceActionItem(
        String text,
        String owner,
        @JsonProperty("due_date") String dueDate
) {
}
