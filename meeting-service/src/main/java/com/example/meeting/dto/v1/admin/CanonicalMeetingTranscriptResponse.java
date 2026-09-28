package com.example.meeting.dto.v1.admin;

import com.example.common.meeting.events.RecordingOutcome;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Owner-authorized readback of the exact transcript occurrence used by one result. */
public record CanonicalMeetingTranscriptResponse(
        UUID analysisRunId,
        UUID meetingId,
        UUID sessionId,
        long finalizationVersion,
        Instant finalizedAt,
        String state,
        String transcript,
        String transcriptSha256,
        int segmentCount,
        List<CanonicalMeetingTranscriptSegment> segments,
        RecordingOutcome recordingOutcome,
        String recordingIncompleteReason) {

    public CanonicalMeetingTranscriptResponse {
        java.util.Objects.requireNonNull(recordingOutcome).validateReason(recordingIncompleteReason);
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
