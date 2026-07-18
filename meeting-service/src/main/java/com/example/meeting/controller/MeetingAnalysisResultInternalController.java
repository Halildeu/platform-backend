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
 * primary key. Re-POSTing the same key and payload with a fresh exact capability is
 * a 200 safe retry. Reusing the same capability is rejected as replay, a different
 * payload is a {@code 409 IDEMPOTENCY_CONFLICT}, and a brand-new key is a 201.
 *
 * <p><b>SECURITY.</b> The auth-service token proves the expected caller class while
 * {@code X-Analysis-Job-Capability} binds that caller to one tenant, meeting,
 * transcript session, finalization occurrence, analysis spec and analysis run.
 * Both gates are required; neither a user token nor a generic service credential
 * can select an arbitrary meeting.
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
            @RequestHeader("X-Analysis-Job-Capability") String jobCapability,
            @Valid @RequestBody MeetingAnalysisResultIngestRequest request) {
        MeetingAnalysisResultIngestResponse body =
                ingestionService.ingest(meetingId, analysisRunId, jobCapability, request);
        // 200 on a fresh-capability retry of an existing run, 201 on a fresh write.
        HttpStatus status = body.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(body);
    }
}
