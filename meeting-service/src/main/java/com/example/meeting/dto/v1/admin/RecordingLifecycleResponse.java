package com.example.meeting.dto.v1.admin;

import com.example.meeting.model.MeetingStatus;
import com.example.meeting.model.TranscriptStatus;

import java.time.Instant;
import java.util.UUID;

/** Canonical projection returned after an attended recording lifecycle sync. */
public record RecordingLifecycleResponse(
        UUID meetingId,
        UUID sessionId,
        String externalSessionId,
        MeetingStatus meetingStatus,
        TranscriptStatus transcriptStatus,
        Instant startedAt,
        Instant endedAt) {
}
