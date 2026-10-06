package com.example.transcript.finalization;

import java.time.Instant;
import java.util.UUID;

/** Two distinct, metadata-only terminal occurrences; never coerce incomplete to finished. */
public sealed interface RecordingClosureEvent permits RecordingFinishedEvent, RecordingIncompleteEvent {
    String eventKey();
    String payloadSha256();
    UUID tenantId();
    UUID meetingId();
    UUID recordingSessionId();
    String externalSessionId();
    Instant closedAt();
    String eventType();
}
