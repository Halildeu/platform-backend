package com.example.meeting.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.meeting.dto.v1.admin.MeetingSessionProcessingStatusResponse;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingSessionProcessingStatusService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/meetings/{meetingId}/sessions/{sessionId}/processing-status")
public class MeetingSessionProcessingStatusController {
    private final MeetingSessionProcessingStatusService service;
    private final TenantContextResolver tenants;

    public MeetingSessionProcessingStatusController(MeetingSessionProcessingStatusService service,
            TenantContextResolver tenants) {
        this.service = service;
        this.tenants = tenants;
    }

    @GetMapping
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.VIEWER)
    public ResponseEntity<MeetingSessionProcessingStatusResponse> read(
            @PathVariable UUID meetingId, @PathVariable UUID sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .body(service.read(tenants.resolveRequired(), meetingId, sessionId));
    }
}
