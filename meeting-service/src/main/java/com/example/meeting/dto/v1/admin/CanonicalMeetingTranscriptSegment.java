package com.example.meeting.dto.v1.admin;

import com.example.common.meeting.events.SpeakerAttribution;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One ordered segment from an immutable canonical transcript occurrence. */
public record CanonicalMeetingTranscriptSegment(String text, double start, Double end,
        @JsonInclude(JsonInclude.Include.NON_NULL) SpeakerAttribution speakerAttribution) {
    public CanonicalMeetingTranscriptSegment(String text, double start, Double end) {
        this(text, start, end, null);
    }
}
