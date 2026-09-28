package com.example.meeting.service;

import com.example.common.meeting.events.RecordingOutcome;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Internal exact-tuple canonical transcript read port. */
public interface CanonicalTranscriptClient {

    Snapshot read(UUID tenantId, UUID meetingId, UUID sessionId, long finalizationVersion,
            UUID analysisRunId, String analysisSpecVersion);

    record Snapshot(
            UUID tenantId,
            UUID meetingId,
            UUID sessionId,
            long finalizationVersion,
            Instant finalizedAt,
            String state,
            String transcript,
            String transcriptSha256,
            int segmentCount,
            List<Segment> segments,
            @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = OutcomeDeserializer.class)
            RecordingOutcome recordingOutcome,
            String recordingIncompleteReason) {
        public Snapshot {
            recordingOutcome = recordingOutcome == null ? RecordingOutcome.UNKNOWN : recordingOutcome;
            recordingOutcome.validateReason(recordingIncompleteReason);
        }
    }

    /** Do not let Jackson coerce a numeric enum ordinal into closure evidence. */
    class OutcomeDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<RecordingOutcome> {
        @Override
        public RecordingOutcome deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if (parser.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_STRING) {
                try {
                    return RecordingOutcome.valueOf(parser.getText());
                } catch (IllegalArgumentException ignored) {
                    // Return the same bounded error for every malformed value.
                }
            }
            throw com.fasterxml.jackson.databind.JsonMappingException.from(parser, "Invalid recording outcome");
        }
    }

    record Segment(String text, double start, Double end) { }

    enum Failure {
        ERASED,
        RETENTION_EXPIRED,
        ERASURE_PENDING,
        INTEGRITY_CONFLICT,
        UNAVAILABLE,
        INVALID_RESPONSE
    }

    class ReadFailure extends IllegalStateException {
        private final Failure failure;

        public ReadFailure(Failure failure) {
            super(failure.name());
            this.failure = failure;
        }

        public Failure failure() { return failure; }
    }
}
