package com.example.transcript.controller;

import com.example.transcript.dto.TranscriptSourceStatusDto;
import com.example.transcript.service.TranscriptSourceStatusService;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal status for one canonical session; does not issue analysis capabilities. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/meetings/{meetingId}/sessions/{sessionId}/source-status")
public class TranscriptSourceStatusInternalController {

    private final TranscriptSourceStatusService service;

    public TranscriptSourceStatusInternalController(TranscriptSourceStatusService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SVC_transcript:canonical:read')")
    public ResponseEntity<TranscriptSourceStatusDto> read(
            @PathVariable UUID tenantId,
            @PathVariable UUID meetingId,
            @PathVariable UUID sessionId,
            @RequestHeader("X-Tenant-Id") UUID requestedTenantId,
            Authentication authentication) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(service.read(tenantId, meetingId, sessionId,
                        requestedTenantId, authentication.getName()));
    }
}
