package com.example.meeting.controller;

import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestRequest;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestResponse;
import com.example.meeting.service.MeetingAnalysisResultIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * INTERNAL analysis-result ingestion endpoint — Faz 24 (platform-ai#244 BE-1c).
 * meeting-ai-service (the single server-side PRODUCER) writes a completed
 * analysis result here; meeting-service (the SYSTEM OF RECORD) persists it in one
 * atomic transaction. The desktop only triggers analysis and reads the canonical
 * result back.
 *
 * <p><b>Idempotency.</b> {@code Idempotency-Key: <analysisRunId UUID>} is the run's
 * primary key. Re-POSTing the same key with the same payload is a 200 replay; with a
 * different payload it is a {@code 409 IDEMPOTENCY_CONFLICT}; a brand-new key is a 201.
 *
 * <p><b>SECURITY (accepted residual risk — read before changing this path).</b>
 * {@code SVC_meeting:analysis-result:write} is a platform-wide system-principal write
 * authority, NOT tenant-scoped. This endpoint does not further constrain the caller's
 * target-meeting selection by a tenant claim or an analysis-job binding. The
 * meeting-derived tenant + the composite tenant FK are relational data-integrity
 * controls, NOT authorization: they stop a payload from binding a run to the wrong
 * tenant, but they do not stop an authorised global principal from targeting any
 * meeting. BE-1b enforces the token CLASS (iss==auth-service, aud==meeting-service,
 * an env-gated service decoder; a Keycloak user token can never mint the SVC_
 * authority), but issuer validity is not caller-workload authorization, and the
 * NetworkPolicy is an additional barrier, not authorization. A compromise of the
 * analyzer service credential therefore carries a write blast radius across all
 * tenants' meetings. The documented future hardening is a server-side analysis-job
 * binding (runId ↔ meetingId ↔ tenantId). The {@code @PreAuthorize} below is
 * defence-in-depth behind the {@code @Order(1)} internal filter chain.
 */
@RestController
@RequestMapping("/api/v1/internal/meetings/{meetingId}/analysis-results")
public class MeetingAnalysisResultInternalController {

    private final MeetingAnalysisResultIngestionService ingestionService;

    public MeetingAnalysisResultInternalController(MeetingAnalysisResultIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SVC_meeting:analysis-result:write')")
    public ResponseEntity<MeetingAnalysisResultIngestResponse> ingest(
            @PathVariable UUID meetingId,
            @RequestHeader("Idempotency-Key") UUID analysisRunId,
            @Valid @RequestBody MeetingAnalysisResultIngestRequest request) {
        MeetingAnalysisResultIngestResponse body =
                ingestionService.ingest(meetingId, analysisRunId, request);
        // 200 on an idempotent replay of an existing run, 201 on a fresh write.
        HttpStatus status = body.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(body);
    }
}
