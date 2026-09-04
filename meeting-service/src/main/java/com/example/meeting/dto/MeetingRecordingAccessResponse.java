package com.example.meeting.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Authorized recorder scope returned after object-level meeting access passes.
 *
 * <p>This response deliberately contains no meeting title, participant,
 * transcript, recording, or other user content. The audio gateway needs only
 * canonical UUID scope to produce tenant-safe meeting events, plus the
 * consent-bound speech-context terms the meeting owner configured on the
 * canonical meeting contract (platform-backend#1024). The gateway merges those
 * terms ahead of the recorder's own terms so live STT biasing follows the
 * meeting contract rather than whatever a single client sends.
 *
 * <p>{@code speechContextTerms} is never null; an empty list means the meeting
 * has no vocabulary.
 */
public record MeetingRecordingAccessResponse(
        UUID meetingId,
        UUID tenantId,
        UUID orgId,
        List<String> speechContextTerms
) {
    public MeetingRecordingAccessResponse {
        speechContextTerms = speechContextTerms == null
                ? List.of()
                : speechContextTerms.stream().filter(Objects::nonNull).toList();
    }
}
