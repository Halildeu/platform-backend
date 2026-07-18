package com.example.meeting.service;

import java.util.UUID;

public interface TranscriptSessionErasureClient {

    Result erase(UUID tenantId, UUID meetingId, UUID sessionId, String sourceSessionId);

    record Result(Status status, int deletedCount) {
        public enum Status { COMPLETE, HELD }
    }
}
