package com.example.meeting.dto.v1.internal;

import java.util.UUID;

/** Canonical, content-free association returned to transcript-service. */
public record MeetingSessionResolutionResponse(
        UUID tenantId,
        UUID orgId,
        UUID meetingId,
        UUID sessionId,
        String externalSessionId) {
}
