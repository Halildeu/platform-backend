package com.example.transcript.controller;

import com.example.transcript.model.TranscriptSessionErasureStatus;
import com.example.transcript.service.TranscriptSessionErasureService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-credential-only idempotent erasure endpoint. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/meetings/{meetingId}/sessions/{sessionId}")
public class TranscriptSessionErasureInternalController {

    private final TranscriptSessionErasureService service;

    public TranscriptSessionErasureInternalController(TranscriptSessionErasureService service) {
        this.service = service;
    }

    @PostMapping("/erasure")
    @PreAuthorize("hasAuthority('SVC_transcript:session:erase')")
    public ResponseEntity<TranscriptSessionErasureService.Result> erase(
            @PathVariable UUID tenantId,
            @PathVariable UUID meetingId,
            @PathVariable UUID sessionId,
            @RequestBody(required = false) ErasureRequest request) {
        var result = service.erase(
                tenantId, meetingId, sessionId, request == null ? null : request.sourceSessionId());
        HttpStatus status = result.status() == TranscriptSessionErasureStatus.HELD
                ? HttpStatus.LOCKED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }

    @PostMapping("/erasure/prepare")
    @PreAuthorize("hasAuthority('SVC_transcript:session:erase')")
    public ResponseEntity<TranscriptSessionErasureService.Result> prepare(
            @PathVariable UUID tenantId,
            @PathVariable UUID meetingId,
            @PathVariable UUID sessionId,
            @RequestBody(required = false) ErasureRequest request) {
        var result = service.prepare(
                tenantId, meetingId, sessionId, request == null ? null : request.sourceSessionId());
        HttpStatus status = result.status() == TranscriptSessionErasureStatus.HELD
                ? HttpStatus.LOCKED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }

    public record ErasureRequest(String sourceSessionId) {
    }
}
