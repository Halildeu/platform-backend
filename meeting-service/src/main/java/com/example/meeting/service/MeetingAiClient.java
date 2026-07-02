package com.example.meeting.service;

import com.example.meeting.dto.v1.admin.MeetingIntelligenceAnalyzeResponse;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceTranscriptSegment;

import java.util.List;
import java.util.UUID;

public interface MeetingAiClient {

    MeetingIntelligenceAnalyzeResponse analyze(
            UUID meetingId,
            String sessionId,
            String transcript,
            List<MeetingIntelligenceTranscriptSegment> segments);
}
