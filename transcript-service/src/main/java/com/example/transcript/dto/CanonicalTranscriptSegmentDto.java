package com.example.transcript.dto;

import com.example.common.meeting.events.SpeakerAttribution;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Ordered, finalized transcript segment returned only to the analysis worker. */
public record CanonicalTranscriptSegmentDto(String text, double start, Double end,
        @JsonInclude(JsonInclude.Include.NON_NULL) SpeakerAttribution speakerAttribution) {
    public CanonicalTranscriptSegmentDto(String text, double start, Double end) {
        this(text, start, end, null);
    }
}
