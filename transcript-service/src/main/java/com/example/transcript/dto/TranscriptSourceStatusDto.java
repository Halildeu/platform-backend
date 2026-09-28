package com.example.transcript.dto;

import com.example.common.meeting.events.RecordingOutcome;
import java.time.Instant;
import java.util.UUID;

/** Observed source metadata, never evidence that AI is running or a result was saved. */
public record TranscriptSourceStatusDto(
        UUID tenantId,
        UUID meetingId,
        UUID sessionId,
        State state,
        Long cycleVersion,
        Long observationRevision,
        Instant observedAt,
        FailureCode failureCode,
        RecordingOutcome recordingOutcome,
        String recordingIncompleteReason,
        FinalizedOccurrence finalizedOccurrence) {

    public enum State { UNKNOWN, AWAITING_CLOSURE, QUIESCING, FINALIZED, FAILED }

    public enum FailureCode { NO_VALID_SEGMENTS_BEFORE_DEADLINE, INVALID_CANONICAL_SEGMENT }

    public record FinalizedOccurrence(
            long finalizationVersion, UUID analysisRunId, Instant finalizedAt,
            RecordingOutcome recordingOutcome, String recordingIncompleteReason) { }
}
