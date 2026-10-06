package com.example.meeting.service;

import com.example.common.meeting.events.RecordingOutcome;
import java.time.Instant;
import java.util.UUID;

/** Source observations are separate from the meeting service's saved-result ledger. */
public interface TranscriptSourceStatusClient {
    Observation read(UUID tenantId, UUID meetingId, UUID sessionId);

    enum State { UNKNOWN, AWAITING_CLOSURE, QUIESCING, FINALIZED, FAILED }
    enum FailureCode { NO_VALID_SEGMENTS_BEFORE_DEADLINE, INVALID_CANONICAL_SEGMENT }
    enum Failure { UNAVAILABLE, INVALID_RESPONSE, ERASED, ERASURE_PENDING, INTEGRITY_CONFLICT }

    record Occurrence(long finalizationVersion, UUID analysisRunId, Instant finalizedAt,
                      RecordingOutcome recordingOutcome, String recordingIncompleteReason) { }

    record Observation(UUID tenantId, UUID meetingId, UUID sessionId, State state,
                       Long cycleVersion, Long observationRevision, Instant observedAt,
                       FailureCode failureCode, RecordingOutcome recordingOutcome,
                       String recordingIncompleteReason, Occurrence finalizedOccurrence) { }

    class ReadFailure extends IllegalStateException {
        private final Failure failure;
        public ReadFailure(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }
        public Failure failure() { return failure; }
    }
}
