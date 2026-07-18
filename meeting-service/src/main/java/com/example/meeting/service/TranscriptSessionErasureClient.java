package com.example.meeting.service;

import java.util.UUID;

public interface TranscriptSessionErasureClient {

    Result prepare(UUID tenantId, UUID meetingId, UUID sessionId, String sourceSessionId);

    Result erase(UUID tenantId, UUID meetingId, UUID sessionId, String sourceSessionId);

    record Result(Status status, int deletedCount) {
        public enum Status { READY, COMPLETE, HELD }
    }
}
