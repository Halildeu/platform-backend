package com.example.meeting.service;

import com.example.meeting.config.MeetingAiProperties;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceAnalyzeRequest;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceAnalyzeResponse;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.security.AdminTenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class MeetingIntelligenceService {

    private final MeetingRepository meetingRepository;
    private final MeetingAiClient meetingAiClient;
    private final MeetingAiProperties properties;

    public MeetingIntelligenceService(
            MeetingRepository meetingRepository,
            MeetingAiClient meetingAiClient,
            MeetingAiProperties properties) {
        this.meetingRepository = meetingRepository;
        this.meetingAiClient = meetingAiClient;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public MeetingIntelligenceAnalyzeResponse analyze(
            AdminTenantContext tenant,
            UUID meetingId,
            MeetingIntelligenceAnalyzeRequest request) {
        if (request.meetingId() != null && !meetingId.equals(request.meetingId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meeting id mismatch.");
        }
        String transcript = request.transcript().trim();
        if (transcript.length() > properties.maxTranscriptChars()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Transcript exceeds Meeting AI limit.");
        }
        requireMeeting(tenant, meetingId);

        return meetingAiClient
                .analyze(meetingId, request.sessionId(), transcript, request.segments())
                .withMeetingEnvelope(meetingId, request.sessionId());
    }

    private void requireMeeting(AdminTenantContext tenant, UUID id) {
        meetingRepository.findVisibleToOrgAndId(tenant.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found."));
    }
}
