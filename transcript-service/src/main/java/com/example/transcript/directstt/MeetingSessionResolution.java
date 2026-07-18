package com.example.transcript.directstt;

import java.util.UUID;

/** Content-free result from the canonical meeting-service resolver. */
public record MeetingSessionResolution(
        Status status,
        UUID tenantId,
        UUID orgId,
        UUID meetingId,
        UUID sessionId,
        String sourceSessionId,
        String errorCode) {

    public static MeetingSessionResolution resolved(
            UUID tenantId, UUID orgId, UUID meetingId, UUID sessionId, String sourceSessionId) {
        return new MeetingSessionResolution(
                Status.RESOLVED, tenantId, orgId, meetingId, sessionId, sourceSessionId, null);
    }

    public static MeetingSessionResolution failure(Status status, String errorCode) {
        return new MeetingSessionResolution(status, null, null, null, null, null, errorCode);
    }

    public enum Status {
        RESOLVED,
        NOT_FOUND,
        UNAVAILABLE,
        INVALID
    }
}
