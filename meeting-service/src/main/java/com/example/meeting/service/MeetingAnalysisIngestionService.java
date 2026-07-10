package com.example.meeting.service;

import com.example.meeting.dto.v1.admin.MeetingIntelligenceActionItem;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceCitation;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceRejectedClaim;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestionRequest;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestionResponse;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingAnalysisCitation;
import com.example.meeting.model.MeetingAnalysisOutboxEvent;
import com.example.meeting.model.MeetingAnalysisRejectedClaim;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingAnalysisRunStatus;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingSummary;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAnalysisCitationRepository;
import com.example.meeting.repository.MeetingAnalysisOutboxEventRepository;
import com.example.meeting.repository.MeetingAnalysisRejectedClaimRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * meeting-ai -> meeting-service single atomic aggregate-ingestion — #244
 * BE-1 (Verdict A). {@link #ingest} is the ONLY write path for analysis
 * results: summary + decisions + actions + outbox events land in one DB
 * transaction, or none of them do (acceptance condition 1 — no partial
 * persistence via a chain of separate REST calls).
 *
 * <p>The synthetic subject {@code "meeting-ai-service"} is written to the
 * existing {@code created_by_subject}/{@code last_updated_by_subject} audit
 * columns on decision/action rows — those columns are {@code NOT NULL} and
 * predate this service-token write path, which has no human subject.
 */
@Service
public class MeetingAnalysisIngestionService {

    private static final String SERVICE_SUBJECT = "meeting-ai-service";
    private static final int TITLE_MAX_LEN = 512;
    private static final int DESCRIPTION_MAX_LEN = 2000;

    private final MeetingRepository meetingRepository;
    private final MeetingAnalysisRunRepository analysisRunRepository;
    private final MeetingSummaryRepository summaryRepository;
    private final MeetingDecisionRepository decisionRepository;
    private final MeetingActionRepository actionRepository;
    private final MeetingAnalysisCitationRepository citationRepository;
    private final MeetingAnalysisRejectedClaimRepository rejectedClaimRepository;
    private final MeetingAnalysisOutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public MeetingAnalysisIngestionService(
            MeetingRepository meetingRepository,
            MeetingAnalysisRunRepository analysisRunRepository,
            MeetingSummaryRepository summaryRepository,
            MeetingDecisionRepository decisionRepository,
            MeetingActionRepository actionRepository,
            MeetingAnalysisCitationRepository citationRepository,
            MeetingAnalysisRejectedClaimRepository rejectedClaimRepository,
            MeetingAnalysisOutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper) {
        this.meetingRepository = meetingRepository;
        this.analysisRunRepository = analysisRunRepository;
        this.summaryRepository = summaryRepository;
        this.decisionRepository = decisionRepository;
        this.actionRepository = actionRepository;
        this.citationRepository = citationRepository;
        this.rejectedClaimRepository = rejectedClaimRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MeetingAnalysisResultIngestionResponse ingest(
            UUID meetingId, MeetingAnalysisResultIngestionRequest request) {
        if (request.meetingId() != null && !meetingId.equals(request.meetingId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meeting id mismatch.");
        }

        String payloadHash = hashPayload(request);

        // Idempotency checks come BEFORE the meeting lookup: a replay of an
        // already-accepted analysisRunId (or logical identity tuple) needs
        // no fresh meeting-existence check and no extra DB round trip — the
        // meeting necessarily existed when the run was first accepted.
        Optional<MeetingAnalysisRun> byRunId =
                analysisRunRepository.findByAnalysisRunId(request.analysisRunId());
        if (byRunId.isPresent()) {
            return replayOrConflict(byRunId.get(), meetingId, payloadHash);
        }

        Optional<MeetingAnalysisRun> byIdentity =
                analysisRunRepository.findByMeetingIdAndTranscriptRevisionAndAnalyzerContractVersion(
                        meetingId, request.transcriptRevision(), request.analyzerContractVersion());
        if (byIdentity.isPresent()) {
            return replayOrConflict(byIdentity.get(), meetingId, payloadHash);
        }

        // Acceptance condition 4: org/tenant scope comes ONLY from the
        // meetingId path variable resolved against meeting-service's own
        // table, never from the caller-supplied payload. Only reached for a
        // genuinely new run.
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found."));

        Optional<MeetingAnalysisRun> currentCanonical =
                analysisRunRepository.findByMeetingIdAndStatus(meetingId, MeetingAnalysisRunStatus.CANONICAL);
        if (currentCanonical.isPresent()
                && !request.generatedAt().isAfter(currentCanonical.get().getGeneratedAt())) {
            // A newer-or-equal canonical analysis already exists for this
            // meeting; an older transcript revision must never overwrite it.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_TRANSCRIPT_ANALYSIS");
        }

        UUID tenantId = meeting.getTenantId();
        UUID orgId = meeting.getEffectiveOrgId();

        currentCanonical.ifPresent(previous -> {
            previous.setStatus(MeetingAnalysisRunStatus.SUPERSEDED);
            previous.setSupersededByAnalysisRunId(request.analysisRunId());
            analysisRunRepository.save(previous);
        });

        MeetingAnalysisRun run = new MeetingAnalysisRun();
        run.setMeetingId(meetingId);
        run.setTenantId(tenantId);
        run.setOrgId(orgId);
        run.setAnalysisRunId(request.analysisRunId());
        run.setTranscriptId(request.transcriptId());
        run.setTranscriptRevision(request.transcriptRevision());
        run.setAnalyzerContractVersion(request.analyzerContractVersion());
        run.setModelVersion(request.modelVersion());
        run.setPromptVersion(request.promptVersion());
        run.setPayloadHash(payloadHash);
        run.setStatus(MeetingAnalysisRunStatus.CANONICAL);
        run.setGeneratedAt(request.generatedAt());
        // #244 acceptance condition 2 names this field explicitly on the NEW
        // run ("yeni analysisRunId + supersedesAnalysisRunId").
        if (currentCanonical.isPresent()) {
            run.setSupersedesAnalysisRunId(currentCanonical.get().getAnalysisRunId());
        }
        run = analysisRunRepository.save(run);

        persistSummary(run, meetingId, tenantId, orgId, request.summary());
        persistDecisions(run, meetingId, tenantId, orgId, request);
        boolean anyActionAssigned = persistActions(run, meetingId, tenantId, orgId, request);
        persistCitations(run, meetingId, tenantId, orgId, request);
        persistRejectedClaims(run, meetingId, tenantId, orgId, request);

        emitOutboxEvent(run, meetingId, tenantId, orgId, "summary.ready", Map.of());
        if (anyActionAssigned) {
            emitOutboxEvent(run, meetingId, tenantId, orgId, "action.assigned", Map.of());
        }

        return new MeetingAnalysisResultIngestionResponse(run.getAnalysisRunId(), meetingId, false);
    }

    private MeetingAnalysisResultIngestionResponse replayOrConflict(
            MeetingAnalysisRun existing, UUID meetingId, String payloadHash) {
        if (!existing.getPayloadHash().equals(payloadHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        }
        return new MeetingAnalysisResultIngestionResponse(existing.getAnalysisRunId(), meetingId, true);
    }

    private void persistSummary(
            MeetingAnalysisRun run, UUID meetingId, UUID tenantId, UUID orgId, String summaryText) {
        MeetingSummary summary = new MeetingSummary();
        summary.setMeetingId(meetingId);
        summary.setTenantId(tenantId);
        summary.setOrgId(orgId);
        summary.setAnalysisRunId(run.getId());
        summary.setSummaryText(summaryText);
        summaryRepository.save(summary);
    }

    private void persistDecisions(
            MeetingAnalysisRun run,
            UUID meetingId,
            UUID tenantId,
            UUID orgId,
            MeetingAnalysisResultIngestionRequest request) {
        for (String decisionText : request.decisions()) {
            if (decisionText == null || decisionText.isBlank()) {
                continue;
            }
            MeetingDecision decision = new MeetingDecision();
            decision.setMeetingId(meetingId);
            decision.setTenantId(tenantId);
            decision.setOrgId(orgId);
            decision.setAnalysisRunId(run.getId());
            decision.setTitle(truncate(decisionText.trim(), TITLE_MAX_LEN));
            decision.setCreatedBySubject(SERVICE_SUBJECT);
            decision.setLastUpdatedBySubject(SERVICE_SUBJECT);
            decisionRepository.save(decision);
        }
    }

    /** Returns true if at least one action had a verified non-null owner. */
    private boolean persistActions(
            MeetingAnalysisRun run,
            UUID meetingId,
            UUID tenantId,
            UUID orgId,
            MeetingAnalysisResultIngestionRequest request) {
        boolean anyAssigned = false;
        for (MeetingIntelligenceActionItem item : request.actions()) {
            if (item.text() == null || item.text().isBlank()) {
                continue;
            }
            MeetingAction action = new MeetingAction();
            action.setMeetingId(meetingId);
            action.setTenantId(tenantId);
            action.setOrgId(orgId);
            action.setAnalysisRunId(run.getId());
            action.setDescription(truncate(item.text().trim(), DESCRIPTION_MAX_LEN));
            // Attribution guard (acceptance condition 3): action.assigned is
            // only emitted for a verified non-null owner; a null/blank owner
            // still persists the action (OPEN, unassigned) but never fires
            // the event, so no downstream consumer assumes a real assignee.
            String owner = item.owner();
            boolean hasOwner = owner != null && !owner.isBlank();
            if (hasOwner) {
                action.setAssigneeSubject(owner.trim());
                anyAssigned = true;
            }
            action.setCreatedBySubject(SERVICE_SUBJECT);
            action.setLastUpdatedBySubject(SERVICE_SUBJECT);
            actionRepository.save(action);
        }
        return anyAssigned;
    }

    private void persistCitations(
            MeetingAnalysisRun run,
            UUID meetingId,
            UUID tenantId,
            UUID orgId,
            MeetingAnalysisResultIngestionRequest request) {
        for (MeetingIntelligenceCitation item : request.citations()) {
            if (item.claim() == null || item.claim().isBlank()) {
                continue;
            }
            MeetingAnalysisCitation citation = new MeetingAnalysisCitation();
            citation.setMeetingId(meetingId);
            citation.setTenantId(tenantId);
            citation.setOrgId(orgId);
            citation.setAnalysisRunId(run.getId());
            citation.setClaim(truncate(item.claim(), 4000));
            citation.setSourceIndex(item.sourceIndex());
            citation.setSourceText(item.sourceText() == null ? null : truncate(item.sourceText(), 4000));
            citation.setSimilarity(item.similarity());
            citation.setGrounded(item.grounded());
            citation.setStatus(item.status());
            citation.setReason(item.reason() == null ? null : truncate(item.reason(), 2000));
            citation.setStartSec(item.startSec());
            citation.setSourceCharStart(item.sourceCharStart());
            citation.setSourceCharEnd(item.sourceCharEnd());
            citation.setSourceHash(item.sourceHash());
            citation.setQuoteHash(item.quoteHash());
            citationRepository.save(citation);
        }
    }

    private void persistRejectedClaims(
            MeetingAnalysisRun run,
            UUID meetingId,
            UUID tenantId,
            UUID orgId,
            MeetingAnalysisResultIngestionRequest request) {
        for (MeetingIntelligenceRejectedClaim item : request.rejectedClaims()) {
            if (item.claim() == null || item.claim().isBlank()) {
                continue;
            }
            MeetingAnalysisRejectedClaim rejectedClaim = new MeetingAnalysisRejectedClaim();
            rejectedClaim.setMeetingId(meetingId);
            rejectedClaim.setTenantId(tenantId);
            rejectedClaim.setOrgId(orgId);
            rejectedClaim.setAnalysisRunId(run.getId());
            rejectedClaim.setClaim(truncate(item.claim(), 4000));
            rejectedClaim.setKind(item.kind());
            rejectedClaim.setStatus(item.status());
            rejectedClaim.setReason(item.reason() == null ? null : truncate(item.reason(), 2000));
            rejectedClaim.setSimilarity(item.similarity());
            rejectedClaimRepository.save(rejectedClaim);
        }
    }

    private void emitOutboxEvent(
            MeetingAnalysisRun run,
            UUID meetingId,
            UUID tenantId,
            UUID orgId,
            String eventType,
            Map<String, Object> extraFields) {
        Map<String, Object> payload = new LinkedHashMap<>(extraFields);
        payload.put("meetingId", meetingId.toString());
        payload.put("analysisRunId", run.getAnalysisRunId());

        MeetingAnalysisOutboxEvent event = new MeetingAnalysisOutboxEvent();
        event.setMeetingId(meetingId);
        event.setTenantId(tenantId);
        event.setOrgId(orgId);
        event.setAnalysisRunId(run.getId());
        event.setEventType(eventType);
        event.setPayload(toJson(payload));
        outboxEventRepository.save(event);
    }

    /**
     * Deterministic content hash for idempotency comparison — deliberately
     * excludes {@code generatedAt} (a producer timestamp, not content) so a
     * byte-identical retry with a fresh timestamp is still recognized as the
     * same payload rather than tripping {@code IDEMPOTENCY_CONFLICT}.
     *
     * <p>Package-private (not private) so {@code MeetingAnalysisIngestionServiceTest}
     * can build replay-matching fixtures without duplicating the algorithm.
     */
    String hashPayload(MeetingAnalysisResultIngestionRequest request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("transcriptId", request.transcriptId());
        canonical.put("transcriptRevision", request.transcriptRevision());
        canonical.put("analyzerContractVersion", request.analyzerContractVersion());
        canonical.put("modelVersion", request.modelVersion());
        canonical.put("promptVersion", request.promptVersion());
        canonical.put("summary", request.summary());
        canonical.put("decisions", request.decisions());
        canonical.put("actions", request.actions());
        canonical.put("citations", request.citations());
        canonical.put("rejectedClaims", request.rejectedClaims());
        String json = toJson(canonical);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize analysis ingestion payload", e);
        }
    }

    private static String truncate(String value, int maxLen) {
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
