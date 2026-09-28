package com.example.transcript.finalization;

import java.time.Instant;
import java.util.UUID;
import com.example.common.meeting.events.RecordingOutcome;

/** Explicit incomplete closure; no successful delivery assertion is carried. */
public record RecordingIncompleteEvent(
        String eventKey, String payloadSha256, UUID tenantId, UUID meetingId,
        UUID recordingSessionId, String externalSessionId, Instant closedAt,
        String reasonCode) implements RecordingClosureEvent {
    public RecordingIncompleteEvent { RecordingOutcome.INCOMPLETE.validateReason(reasonCode); }
    @Override public String eventType() { return "meeting.recording.incomplete"; }
}
