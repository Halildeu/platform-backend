package com.example.meeting.controller;

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
 * <p>The endpoint intentionally returns no body: 204 means the caller may
 * record; 403/404 deny without exposing meeting data to the gateway.
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
    public ResponseEntity<Void> checkRecordingAccess(@PathVariable UUID id) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        meetingService.requireRecordingAccess(tenant, id);
        return ResponseEntity.noContent().build();
    }
}
