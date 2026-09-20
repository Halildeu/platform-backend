package com.example.transcript.controller;

import com.example.common.meeting.speakers.SpeakerLabels;
import com.example.transcript.service.CanonicalSpeakerLabelService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Names have their own read/write permissions; canonical analysis readers receive no names. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/meetings/{meetingId}/sessions/{sessionId}/finalizations/{version}/speaker-labels")
public class CanonicalSpeakerLabelController {
    private final CanonicalSpeakerLabelService service;
    public CanonicalSpeakerLabelController(CanonicalSpeakerLabelService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAuthority('SVC_transcript:speaker-label:read')")
    public ResponseEntity<SpeakerLabels.Snapshot> read(@PathVariable UUID tenantId, @PathVariable UUID meetingId,
            @PathVariable UUID sessionId, @PathVariable long version, @RequestHeader("X-Tenant-Id") UUID requestedTenant,
            @RequestHeader("X-Analysis-Run-Id") UUID run, @RequestHeader("X-Analysis-Spec-Version") String spec,
            @RequestHeader("X-Actor-Subject") String actor) {
        return response(service.read(tenantId, meetingId, sessionId, version, requestedTenant, run, spec, actor));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('SVC_transcript:speaker-label:write')")
    public ResponseEntity<SpeakerLabels.Snapshot> edit(@PathVariable UUID tenantId, @PathVariable UUID meetingId,
            @PathVariable UUID sessionId, @PathVariable long version, @RequestHeader("X-Tenant-Id") UUID requestedTenant,
            @RequestHeader("X-Analysis-Run-Id") UUID run, @RequestHeader("X-Analysis-Spec-Version") String spec,
            @RequestHeader("X-Actor-Subject") String actor, @RequestBody SpeakerLabels.Edit edit) {
        return response(service.edit(tenantId, meetingId, sessionId, version, requestedTenant, run, spec, actor, edit));
    }
    private ResponseEntity<SpeakerLabels.Snapshot> response(SpeakerLabels.Snapshot value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(value);
    }
}
