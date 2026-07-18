package com.example.transcript.finalization;

import java.time.Instant;
import java.util.UUID;

/** Strict, metadata-only projection of meeting.recording.finished v1. */
public record RecordingFinishedEvent(
        String eventKey,
        String payloadSha256,
        UUID tenantId,
        UUID meetingId,
        UUID recordingSessionId,
        String externalSessionId,
        Instant finishedAt) {
}
