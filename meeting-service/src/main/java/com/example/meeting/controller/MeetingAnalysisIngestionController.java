package com.example.meeting.controller;

import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestionRequest;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestionResponse;
import com.example.meeting.service.MeetingAnalysisIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Internal, service-token-only aggregate-ingestion endpoint for meeting-ai
 * analysis results — #244 BE-1 (Verdict A).
 *
 * <p>Not under {@code /api/v1/admin/**}: this is not a user-facing admin
 * route, it is called by meeting-ai-service with a service token (see
 * {@code SecurityConfig#internalSecurityFilterChain}, {@code aud=meeting-service},
 * {@code scope=meeting:analysis-result:write}). There is deliberately no
 * {@code @RequireModule} OpenFGA check here — OpenFGA authorizes human/desktop
 * principals against resources; a validated service identity with the
 * narrow write scope IS the authorization for this route.
 */
@RestController
@RequestMapping("/internal/v1/meetings/{meetingId}/analysis-results")
public class MeetingAnalysisIngestionController {

    private final MeetingAnalysisIngestionService meetingAnalysisIngestionService;

    public MeetingAnalysisIngestionController(
            MeetingAnalysisIngestionService meetingAnalysisIngestionService) {
        this.meetingAnalysisIngestionService = meetingAnalysisIngestionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public MeetingAnalysisResultIngestionResponse ingest(
            @PathVariable UUID meetingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MeetingAnalysisResultIngestionRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required.");
        }
        if (!idempotencyKey.equals(request.analysisRunId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Idempotency-Key header must equal analysis_run_id in the body.");
        }
        return meetingAnalysisIngestionService.ingest(meetingId, request);
    }
}
