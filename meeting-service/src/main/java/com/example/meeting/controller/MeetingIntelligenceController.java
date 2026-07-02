package com.example.meeting.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceAnalyzeRequest;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceAnalyzeResponse;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingIntelligenceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/meetings/{meetingId}/intelligence")
public class MeetingIntelligenceController {

    private final MeetingIntelligenceService meetingIntelligenceService;
    private final TenantContextResolver tenantContextResolver;

    public MeetingIntelligenceController(
            MeetingIntelligenceService meetingIntelligenceService,
            TenantContextResolver tenantContextResolver) {
        this.meetingIntelligenceService = meetingIntelligenceService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/analyze")
    @RequireModule(value = MeetingAuthz.MODULE, relation = MeetingAuthz.MANAGER)
    public MeetingIntelligenceAnalyzeResponse analyze(
            @PathVariable UUID meetingId,
            @Valid @RequestBody MeetingIntelligenceAnalyzeRequest request) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        return meetingIntelligenceService.analyze(tenant, meetingId, request);
    }
}
