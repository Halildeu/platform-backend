package com.example.meeting.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.meeting.dto.v1.admin.CanonicalMeetingTranscriptResponse;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingCanonicalTranscriptService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User-token owner read of the transcript bound to one persisted analysis result. */
@RestController
@RequestMapping("/api/v1/admin/meetings/{meetingId}/intelligence/results/{analysisRunId}/transcript")
public class MeetingCanonicalTranscriptController {

    private final MeetingCanonicalTranscriptService service;
    private final TenantContextResolver tenantContextResolver;

    public MeetingCanonicalTranscriptController(
            MeetingCanonicalTranscriptService service,
            TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.VIEWER)
    public ResponseEntity<CanonicalMeetingTranscriptResponse> read(
            @PathVariable UUID meetingId,
            @PathVariable UUID analysisRunId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.read(tenant, meetingId, analysisRunId));
    }
}
