package com.example.meeting.service;

import com.example.meeting.dto.v1.admin.MeetingIntelligenceActionItem;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceCitation;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceRejectedClaim;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceResultResponse;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.security.AdminTenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Reads one run-bound canonical Meeting Intelligence aggregate. */
@Service
public class MeetingIntelligenceResultService {

    private static final String STORAGE_MODE = "canonical";
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Set<String> REJECTED_CLAIM_KINDS = Set.of(
            "summary", "decision", "action", "action_owner", "action_due_date");
    private static final Set<String> REJECTED_CLAIM_STATUSES =
            Set.of("FAILED", "LOW_CONFIDENCE");

    private final MeetingRepository meetingRepository;
    private final MeetingAnalysisRunRepository runRepository;
    private final MeetingDecisionRepository decisionRepository;
    private final MeetingActionRepository actionRepository;
    private final ObjectMapper objectMapper;

    public MeetingIntelligenceResultService(
            MeetingRepository meetingRepository,
            MeetingAnalysisRunRepository runRepository,
            MeetingDecisionRepository decisionRepository,
            MeetingActionRepository actionRepository,
            ObjectMapper objectMapper) {
        this.meetingRepository = meetingRepository;
        this.runRepository = runRepository;
        this.decisionRepository = decisionRepository;
        this.actionRepository = actionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public MeetingIntelligenceResultResponse getLatest(
            AdminTenantContext tenant,
            UUID meetingId) {
        meetingRepository.findVisibleToOrgAndId(tenant.tenantId(), meetingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND"));

        MeetingAnalysisRun run = runRepository
                .findLatestByMeetingIdVisibleToOrg(meetingId, tenant.tenantId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "ANALYSIS_RESULT_NOT_FOUND"));

        List<MeetingDecision> decisions = decisionRepository
                .findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                        run.getAnalysisRunId(), meetingId, tenant.tenantId());
        List<MeetingAction> actions = actionRepository
                .findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                        run.getAnalysisRunId(), meetingId, tenant.tenantId());

        return new MeetingIntelligenceResultResponse(
                run.getAnalysisRunId(),
                meetingId,
                run.getTranscriptSessionId(),
                run.getAnalyzerContractVersion(),
                run.getModel(),
                run.getBackend(),
                run.getPromptVersion(),
                run.getSummary(),
                run.getSummaryGroundingStatus(),
                readEvidence(
                        run.getSummaryCitations(),
                        MeetingIntelligenceCitation.class,
                        MeetingIntelligenceResultService::validCitation),
                decisions.stream().map(MeetingIntelligenceResultService::decisionText).toList(),
                actions.stream().map(MeetingIntelligenceResultService::actionItem).toList(),
                readEvidence(
                        run.getCitations(),
                        MeetingIntelligenceCitation.class,
                        MeetingIntelligenceResultService::validCitation),
                readEvidence(
                        run.getRejectedClaims(),
                        MeetingIntelligenceRejectedClaim.class,
                        MeetingIntelligenceResultService::validRejectedClaim),
                run.getUngroundedCount(),
                run.isRedacted(),
                run.getRedactionCount(),
                run.getGeneratedAt(),
                run.getSupersedesAnalysisRunId(),
                true,
                STORAGE_MODE);
    }

    private static String decisionText(MeetingDecision decision) {
        return decision.getDetail() == null ? decision.getTitle() : decision.getDetail();
    }

    private static MeetingIntelligenceActionItem actionItem(MeetingAction action) {
        return new MeetingIntelligenceActionItem(
                action.getDescription(),
                action.getAssigneeSubject(),
                action.getDueAt() == null ? null : action.getDueAt().toString());
    }

    private <T> List<T> readEvidence(
            String json,
            Class<T> elementType,
            Predicate<T> elementValidator) {
        JavaType listType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, elementType);
        try {
            List<T> values = objectMapper.readValue(json, listType);
            if (values == null || values.stream().anyMatch(value -> !elementValidator.test(value))) {
                throw invalidEvidence();
            }
            return List.copyOf(values);
        } catch (JsonProcessingException ignored) {
            // Parser details can contain source excerpts; keep evidence out of error chains and logs.
            throw invalidEvidence();
        }
    }

    private static boolean validCitation(MeetingIntelligenceCitation citation) {
        return citation != null
                && citation.claim() != null && !citation.claim().isBlank()
                && citation.sourceIndex() != null && citation.sourceIndex() >= 0
                && citation.sourceText() != null && !citation.sourceText().isBlank()
                && inUnitInterval(citation.similarity())
                && Boolean.TRUE.equals(citation.grounded())
                && "PASSED".equals(citation.status())
                && citation.reason() != null
                && (citation.startSec() == null || citation.startSec() >= 0.0)
                && citation.sourceCharStart() != null && citation.sourceCharStart() >= 0
                && citation.sourceCharEnd() != null
                && citation.sourceCharEnd() > citation.sourceCharStart()
                && validSha256(citation.sourceHash())
                && validSha256(citation.quoteHash());
    }

    private static boolean validRejectedClaim(MeetingIntelligenceRejectedClaim claim) {
        return claim != null
                && claim.claim() != null && !claim.claim().isBlank()
                && REJECTED_CLAIM_KINDS.contains(claim.kind())
                && REJECTED_CLAIM_STATUSES.contains(claim.status())
                && claim.reason() != null
                && inUnitInterval(claim.similarity());
    }

    private static boolean inUnitInterval(Double value) {
        return value != null && value >= 0.0 && value <= 1.0;
    }

    private static boolean validSha256(String value) {
        return value != null && SHA256_HEX.matcher(value).matches();
    }

    private static ResponseStatusException invalidEvidence() {
        return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "ANALYSIS_RESULT_INVALID");
    }

}
