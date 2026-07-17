package com.example.meeting.controller;

import com.example.meeting.dto.MeetingRecordingAccessResponse;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Non-admin recorder preflight surface for audio-gateway.
 *
 * <p>A 200 response contains only canonical UUID scope required for a
 * tenant-safe recorder event. 403/404 deny without exposing meeting content.
 */
@RestController
@RequestMapping("/api/v1/meetings")
public class MeetingRecordingAccessController {

    private final MeetingService meetingService;
    private final TenantContextResolver tenantContextResolver;

    public MeetingRecordingAccessController(
            MeetingService meetingService,
            TenantContextResolver tenantContextResolver) {
        this.meetingService = meetingService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/{id}/recording-access")
    public ResponseEntity<MeetingRecordingAccessResponse> checkRecordingAccess(@PathVariable UUID id) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return ResponseEntity.ok(meetingService.requireRecordingAccess(tenant, id));
    }
}
