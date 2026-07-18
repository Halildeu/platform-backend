package com.example.meeting.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Internal exact-tuple canonical transcript read port. */
public interface CanonicalTranscriptClient {

    Snapshot read(UUID tenantId, UUID meetingId, UUID sessionId, long finalizationVersion);

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
            List<Segment> segments) { }

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
