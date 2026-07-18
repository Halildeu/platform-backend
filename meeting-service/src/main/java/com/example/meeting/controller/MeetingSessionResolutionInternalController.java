package com.example.meeting.controller;

import com.example.meeting.dto.v1.internal.MeetingSessionResolutionRequest;
import com.example.meeting.dto.v1.internal.MeetingSessionResolutionResponse;
import com.example.meeting.service.MeetingService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-token-only resolver for external recorder ids.
 *
 * <p>The request is always scoped by tenant and meeting. A missing or foreign
 * association is the same 404, so this endpoint cannot probe another tenant.
 * It returns identifiers only; meeting/transcript/audio content is absent.
 */
@RestController
@RequestMapping("/api/v1/internal/meetings/{meetingId}/sessions/resolve")
public class MeetingSessionResolutionInternalController {

    private final MeetingService meetingService;

    public MeetingSessionResolutionInternalController(MeetingService meetingService) {
        this.meetingService = meetingService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SVC_meeting:session:resolve')")
    public MeetingSessionResolutionResponse resolve(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingSessionResolutionRequest request) {
        return meetingService.resolveSession(
                request.tenantId(), meetingId, request.externalSessionId());
    }
}
