package com.example.meeting.dto.v1.admin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MeetingIntelligenceCitation(
        String claim,
        @JsonProperty("source_index") Integer sourceIndex,
        @JsonProperty("source_text") String sourceText,
        Double similarity,
        Boolean grounded,
        String status,
        String reason,
        @JsonProperty("start_sec") Double startSec,
        @JsonProperty("source_char_start") Integer sourceCharStart,
        @JsonProperty("source_char_end") Integer sourceCharEnd,
        @JsonProperty("source_hash") String sourceHash,
        @JsonProperty("quote_hash") String quoteHash
) {
}
