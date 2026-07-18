package com.example.transcript.controller;

import com.example.transcript.dto.CanonicalTranscriptSnapshotDto;
import com.example.transcript.service.CanonicalTranscriptReadService;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Least-privilege internal read of one exact finalized transcript snapshot. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/meetings/{meetingId}/sessions/{sessionId}/finalizations")
public class CanonicalTranscriptInternalController {

    public static final String CAPABILITY_HEADER = "X-Analysis-Job-Capability";
    public static final String CAPABILITY_EXPIRES_HEADER = "X-Analysis-Job-Capability-Expires-At";

    private final CanonicalTranscriptReadService service;

    public CanonicalTranscriptInternalController(CanonicalTranscriptReadService service) {
        this.service = service;
    }

    @GetMapping("/{finalizationVersion}")
    @PreAuthorize("hasAuthority('SVC_transcript:canonical:read')")
    public ResponseEntity<CanonicalTranscriptSnapshotDto> read(
            @PathVariable UUID tenantId,
            @PathVariable UUID meetingId,
            @PathVariable UUID sessionId,
            @PathVariable long finalizationVersion,
            @RequestHeader("X-Tenant-Id") UUID requestedTenantId,
            Authentication authentication) {
        CanonicalTranscriptSnapshotDto snapshot = service.read(
                tenantId,
                meetingId,
                sessionId,
                finalizationVersion,
                requestedTenantId,
                authentication.getName());
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.setPragma("no-cache");
        return ResponseEntity.ok().headers(headers).body(snapshot);
    }

    @PostMapping("/{finalizationVersion}/analysis-capability")
    @PreAuthorize("hasAuthority('SVC_transcript:analysis-job-capability:issue')")
    public ResponseEntity<Void> issueAnalysisCapability(
            @PathVariable UUID tenantId,
            @PathVariable UUID meetingId,
            @PathVariable UUID sessionId,
            @PathVariable long finalizationVersion,
            @RequestHeader("X-Tenant-Id") UUID requestedTenantId,
            @RequestHeader("X-Analysis-Run-Id") UUID analysisRunId,
            @RequestHeader("X-Analysis-Spec-Version") String analysisSpecVersion) {
        var capability = service.issueAnalysisCapability(
                tenantId, meetingId, sessionId, finalizationVersion,
                requestedTenantId, analysisRunId, analysisSpecVersion);
        HttpHeaders headers = new HttpHeaders();
        headers.set(CAPABILITY_HEADER, capability.token());
        headers.set(CAPABILITY_EXPIRES_HEADER, capability.expiresAt().toString());
        headers.setCacheControl("no-store");
        headers.setPragma("no-cache");
        return ResponseEntity.noContent().headers(headers).build();
    }
}
