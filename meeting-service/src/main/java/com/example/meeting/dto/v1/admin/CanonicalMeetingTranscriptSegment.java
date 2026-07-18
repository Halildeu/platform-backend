package com.example.meeting.dto.v1.admin;

/** One ordered segment from an immutable canonical transcript occurrence. */
public record CanonicalMeetingTranscriptSegment(String text, double start, Double end) { }
