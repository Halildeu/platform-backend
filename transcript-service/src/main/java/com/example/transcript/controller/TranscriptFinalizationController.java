package com.example.transcript.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.transcript.dto.FinalizeTranscriptRequest;
import com.example.transcript.dto.TranscriptFinalizationDto;
import com.example.transcript.security.AdminTenantContext;
import com.example.transcript.security.TenantContextResolver;
import com.example.transcript.security.TranscriptAuthz;
import com.example.transcript.service.TranscriptFinalizationService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit manager-only transition from canonical transcript to ready event. */
@RestController
@RequestMapping("/api/v1/admin/transcripts/meetings/{meetingId}/sessions/{sessionId}/finalizations")
@RequireModule(value = TranscriptAuthz.MODULE, relation = TranscriptAuthz.MANAGER)
public class TranscriptFinalizationController {

    private final TranscriptFinalizationService service;
    private final TenantContextResolver tenantContextResolver;

    public TranscriptFinalizationController(
            TranscriptFinalizationService service,
            TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping
    public TranscriptFinalizationDto finalizeTranscript(
            @PathVariable UUID meetingId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody FinalizeTranscriptRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.finalizeTranscript(
                context, meetingId, sessionId, request.finalizationVersion());
    }
}
