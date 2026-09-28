package com.example.meeting.dto.v1.admin;

import com.example.common.meeting.events.RecordingOutcome;
import com.example.meeting.service.TranscriptSourceStatusClient;
import java.time.Instant;
import java.util.UUID;

/** Two separately timed observations, never an assertion about AI worker activity. */
public record MeetingSessionProcessingStatusResponse(
        UUID meetingId, UUID sessionId,
        TranscriptSourceStatusClient.Observation source, SavedResult savedResult) {
    public enum SavedState { AVAILABLE, NOT_FOUND }

    public record SavedResult(SavedState state, Instant observedAt, UUID analysisRunId,
                              Long finalizationVersion, Instant finalizedAt,
                              RecordingOutcome recordingOutcome, String recordingIncompleteReason,
                              Boolean matchesCurrentSourceOccurrence) { }
}
