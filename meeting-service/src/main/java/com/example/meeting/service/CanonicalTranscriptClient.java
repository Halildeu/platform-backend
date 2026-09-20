package com.example.meeting.service;

import com.example.common.meeting.events.SpeakerAttribution;
import com.example.common.meeting.speakers.SpeakerLabels;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Internal exact-tuple canonical transcript read port. */
public interface CanonicalTranscriptClient {

    Snapshot read(UUID tenantId, UUID meetingId, UUID sessionId, long finalizationVersion,
            UUID analysisRunId, String analysisSpecVersion);

    SpeakerLabels.Snapshot speakerLabels(UUID tenant, UUID meeting, UUID session, long version,
            UUID run, String spec, String actor, SpeakerLabels.Edit edit);

    class SpeakerLabelFailure extends IllegalStateException {
        private final int status;
        public SpeakerLabelFailure(int status) { super("SPEAKER_LABEL_REQUEST_FAILED"); this.status = status; }
        public int status() { return status; }
    }

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

    record Segment(String text, double start, Double end, SpeakerAttribution speakerAttribution) {
        public Segment(String text, double start, Double end) {
            this(text, start, end, null);
        }
    }

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
