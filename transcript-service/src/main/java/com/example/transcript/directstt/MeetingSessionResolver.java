package com.example.transcript.directstt;

import java.util.UUID;

/** Port for resolving an external recorder id to canonical meeting_sessions.id. */
public interface MeetingSessionResolver {
    MeetingSessionResolution resolve(UUID tenantId, UUID meetingId, String sourceSessionId);
}
