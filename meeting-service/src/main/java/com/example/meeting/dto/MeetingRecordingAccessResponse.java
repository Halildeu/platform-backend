package com.example.meeting.dto;

import java.util.UUID;

/**
 * Authorized recorder scope returned after object-level meeting access passes.
 *
 * <p>This response deliberately contains no meeting title, participant,
 * transcript, recording, or other user content. The audio gateway needs only
 * canonical UUID scope to produce tenant-safe meeting events.
 */
public record MeetingRecordingAccessResponse(
        UUID meetingId,
        UUID tenantId,
        UUID orgId
) {
}
