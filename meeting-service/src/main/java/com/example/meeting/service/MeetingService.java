package com.example.meeting.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.MeetingActionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingActionResponse;
import com.example.meeting.dto.v1.admin.MeetingActionUpdateRequest;
import com.example.meeting.dto.v1.admin.MeetingAnalysisResultResponse;
import com.example.meeting.dto.v1.admin.MeetingCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingDecisionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingDecisionResponse;
import com.example.meeting.dto.v1.admin.MeetingDecisionUpdateRequest;
import com.example.meeting.dto.v1.admin.MeetingResponse;
import com.example.meeting.dto.v1.admin.MeetingSessionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingSessionResponse;
import com.example.meeting.dto.v1.admin.MeetingSessionUpdateRequest;
import com.example.meeting.dto.v1.admin.MeetingUpdateRequest;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingActionStatus;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingAnalysisRunStatus;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingSession;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.model.MeetingSummary;
import com.example.meeting.model.TranscriptStatus;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import com.example.meeting.repository.MeetingSummaryRepository;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Meeting CRUD + session/action/decision sub-resource management —
 * Faz 24 (#410).
 *
 * <p>All operations are org-scoped: reads use the canonical effective-org
 * predicate ({@code orgId = :orgId OR (orgId IS NULL AND tenantId =
 * :orgId)}), writes stamp {@code tenantId} from the bound
 * {@link AdminTenantContext} (the V1 trigger back-fills {@code orgId}).
 * Audit columns ({@code createdBySubject} / {@code lastUpdatedBySubject})
 * come from the context's subject, never the request body.
 *
 * <p>Sub-resources are reachable only through a parent meeting the caller
 * can see — every sub-resource op first resolves the meeting via
 * {@code findVisibleToOrgAndId} (404 with no existence leak otherwise),
 * then the child via {@code findByIdAndMeetingIdVisibleToOrg}.
 *
 * <p>On meeting create the service writes a Zanzibar
 * {@code owner @ meeting:&lt;id&gt;} tuple binding the creator (ADR-0012-EA:
 * the service owns the object it just created). The OpenFGA client is
 * optional — under local/dev/test/integration no
 * {@link OpenFgaAuthzService} bean exists, so the tuple write is skipped
 * (the {@code isEnabled()} guard inside the SDK is also a no-op when
 * disabled).
 */
@Service
public class MeetingService {

    private final MeetingRepository meetingRepository;
    private final MeetingSessionRepository sessionRepository;
    private final MeetingActionRepository actionRepository;
    private final MeetingDecisionRepository decisionRepository;
    private final MeetingAnalysisRunRepository analysisRunRepository;
    private final MeetingSummaryRepository summaryRepository;
    private final ObjectProvider<OpenFgaAuthzService> authzServiceProvider;

    public MeetingService(
            MeetingRepository meetingRepository,
            MeetingSessionRepository sessionRepository,
            MeetingActionRepository actionRepository,
            MeetingDecisionRepository decisionRepository,
            MeetingAnalysisRunRepository analysisRunRepository,
            MeetingSummaryRepository summaryRepository,
            ObjectProvider<OpenFgaAuthzService> authzServiceProvider) {
        this.meetingRepository = meetingRepository;
        this.sessionRepository = sessionRepository;
        this.actionRepository = actionRepository;
        this.decisionRepository = decisionRepository;
        this.analysisRunRepository = analysisRunRepository;
        this.summaryRepository = summaryRepository;
        this.authzServiceProvider = authzServiceProvider;
    }

    // ───────────────────────────── Meeting CRUD ─────────────────────────────

    @Transactional(readOnly = true)
    public Page<MeetingResponse> listMeetings(AdminTenantContext tenant, MeetingStatus status, Pageable pageable) {
        Page<Meeting> page = (status == null)
                ? meetingRepository.findAllVisibleToOrg(tenant.tenantId(), pageable)
                : meetingRepository.findAllVisibleToOrgByStatus(tenant.tenantId(), status, pageable);
        return page.map(this::toMeetingResponse);
    }

    @Transactional(readOnly = true)
    public MeetingResponse getMeeting(AdminTenantContext tenant, UUID id) {
        return toMeetingResponse(requireMeeting(tenant, id));
    }

    @Transactional(readOnly = true)
    public void requireRecordingAccess(AdminTenantContext tenant, UUID id) {
        requireMeeting(tenant, id);
        String principalRef = toUserPrincipalRef(tenant.authzPrincipal());

        OpenFgaAuthzService authz = authzServiceProvider.getIfAvailable();
        if (authz == null || !authz.isEnabled()) {
            return;
        }

        boolean allowed = authz.checkPrincipal(
                principalRef,
                MeetingAuthz.CAN_RECORD,
                MeetingAuthz.OBJECT_TYPE,
                id.toString());
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Meeting recording access denied.");
        }
    }

    @Transactional
    public MeetingResponse createMeeting(AdminTenantContext tenant, MeetingCreateRequest request) {
        Meeting meeting = new Meeting();
        meeting.setTenantId(tenant.tenantId());
        meeting.setOrgId(tenant.tenantId()); // canonical writer sets BOTH columns (trigger is backstop)
        meeting.setTitle(request.title());
        meeting.setDescription(request.description());
        meeting.setStatus(MeetingStatus.SCHEDULED);
        meeting.setScheduledStart(request.scheduledStart());
        meeting.setScheduledEnd(request.scheduledEnd());
        meeting.setOrganizerSubject(
                isBlank(request.organizerSubject()) ? tenant.subject() : request.organizerSubject().trim());
        meeting.setCreatedBySubject(tenant.subject());
        meeting.setLastUpdatedBySubject(tenant.subject());

        Meeting saved = meetingRepository.save(meeting);
        // Owner tuple uses the SAME principal the module gate authorizes with
        // (userId-claim-or-sub), not the audit subject — see AdminTenantContext.
        writeOwnerTuple(tenant.authzPrincipal(), saved.getId());
        return toMeetingResponse(saved);
    }

    @Transactional
    public MeetingResponse updateMeeting(AdminTenantContext tenant, UUID id, MeetingUpdateRequest request) {
        Meeting meeting = requireMeeting(tenant, id);
        requireExpectedVersion(request.expectedVersion(), meeting.getVersion());
        meeting.setTitle(request.title());
        meeting.setDescription(request.description());
        meeting.setStatus(request.status());
        if (!isBlank(request.organizerSubject())) {
            meeting.setOrganizerSubject(request.organizerSubject().trim());
        }
        meeting.setScheduledStart(request.scheduledStart());
        meeting.setScheduledEnd(request.scheduledEnd());
        meeting.setLastUpdatedBySubject(tenant.subject());
        return toMeetingResponse(meetingRepository.save(meeting));
    }

    @Transactional
    public void deleteMeeting(AdminTenantContext tenant, UUID id) {
        Meeting meeting = requireMeeting(tenant, id);
        // Explicitly delete children so the persistence context stays
        // consistent (the DB FK ON DELETE CASCADE is the structural
        // backstop). Order: leaf rows before the parent.
        sessionRepository.deleteAll(
                sessionRepository.findByMeetingIdVisibleToOrg(meeting.getId(), tenant.tenantId()));
        actionRepository.deleteAll(
                actionRepository.findByMeetingIdVisibleToOrg(meeting.getId(), tenant.tenantId()));
        decisionRepository.deleteAll(
                decisionRepository.findByMeetingIdVisibleToOrg(meeting.getId(), tenant.tenantId()));
        meetingRepository.delete(meeting);
    }

    // ──────────────────────────── Sessions ────────────────────────────

    @Transactional(readOnly = true)
    public List<MeetingSessionResponse> listSessions(AdminTenantContext tenant, UUID meetingId) {
        requireMeeting(tenant, meetingId);
        return sessionRepository.findByMeetingIdVisibleToOrg(meetingId, tenant.tenantId())
                .stream().map(this::toSessionResponse).toList();
    }

    @Transactional(readOnly = true)
    public MeetingSessionResponse getSession(AdminTenantContext tenant, UUID meetingId, UUID sessionId) {
        requireMeeting(tenant, meetingId);
        return toSessionResponse(requireSession(tenant, meetingId, sessionId));
    }

    @Transactional
    public MeetingSessionResponse createSession(
            AdminTenantContext tenant, UUID meetingId, MeetingSessionCreateRequest request) {
        Meeting meeting = requireMeeting(tenant, meetingId);
        MeetingSession session = new MeetingSession();
        session.setMeetingId(meeting.getId());
        session.setTenantId(tenant.tenantId());
        session.setOrgId(tenant.tenantId()); // canonical writer sets BOTH columns (trigger is backstop)
        session.setSessionLabel(request.sessionLabel());
        session.setStartedAt(request.startedAt());
        session.setEndedAt(request.endedAt());
        session.setRecordingUri(request.recordingUri());
        session.setTranscriptStatus(TranscriptStatus.PENDING);
        session.setCreatedBySubject(tenant.subject());
        session.setLastUpdatedBySubject(tenant.subject());
        return toSessionResponse(sessionRepository.save(session));
    }

    @Transactional
    public MeetingSessionResponse updateSession(
            AdminTenantContext tenant, UUID meetingId, UUID sessionId, MeetingSessionUpdateRequest request) {
        requireMeeting(tenant, meetingId);
        MeetingSession session = requireSession(tenant, meetingId, sessionId);
        requireExpectedVersion(request.expectedVersion(), session.getVersion());
        session.setSessionLabel(request.sessionLabel());
        session.setStartedAt(request.startedAt());
        session.setEndedAt(request.endedAt());
        session.setRecordingUri(request.recordingUri());
        session.setTranscriptStatus(request.transcriptStatus());
        session.setLastUpdatedBySubject(tenant.subject());
        return toSessionResponse(sessionRepository.save(session));
    }

    @Transactional
    public void deleteSession(AdminTenantContext tenant, UUID meetingId, UUID sessionId) {
        requireMeeting(tenant, meetingId);
        sessionRepository.delete(requireSession(tenant, meetingId, sessionId));
    }

    // ──────────────────────────── Actions ────────────────────────────

    @Transactional(readOnly = true)
    public List<MeetingActionResponse> listActions(AdminTenantContext tenant, UUID meetingId) {
        requireMeeting(tenant, meetingId);
        return actionRepository.findByMeetingIdVisibleToOrg(meetingId, tenant.tenantId())
                .stream().map(this::toActionResponse).toList();
    }

    @Transactional(readOnly = true)
    public MeetingActionResponse getAction(AdminTenantContext tenant, UUID meetingId, UUID actionId) {
        requireMeeting(tenant, meetingId);
        return toActionResponse(requireAction(tenant, meetingId, actionId));
    }

    @Transactional
    public MeetingActionResponse createAction(
            AdminTenantContext tenant, UUID meetingId, MeetingActionCreateRequest request) {
        Meeting meeting = requireMeeting(tenant, meetingId);
        MeetingAction action = new MeetingAction();
        action.setMeetingId(meeting.getId());
        action.setTenantId(tenant.tenantId());
        action.setOrgId(tenant.tenantId()); // canonical writer sets BOTH columns (trigger is backstop)
        action.setDescription(request.description());
        action.setAssigneeSubject(request.assigneeSubject());
        action.setStatus(MeetingActionStatus.OPEN);
        action.setDueAt(request.dueAt());
        action.setCreatedBySubject(tenant.subject());
        action.setLastUpdatedBySubject(tenant.subject());
        return toActionResponse(actionRepository.save(action));
    }

    @Transactional
    public MeetingActionResponse updateAction(
            AdminTenantContext tenant, UUID meetingId, UUID actionId, MeetingActionUpdateRequest request) {
        requireMeeting(tenant, meetingId);
        MeetingAction action = requireAction(tenant, meetingId, actionId);
        requireExpectedVersion(request.expectedVersion(), action.getVersion());
        action.setDescription(request.description());
        action.setAssigneeSubject(request.assigneeSubject());
        action.setStatus(request.status());
        action.setDueAt(request.dueAt());
        action.setLastUpdatedBySubject(tenant.subject());
        return toActionResponse(actionRepository.save(action));
    }

    @Transactional
    public void deleteAction(AdminTenantContext tenant, UUID meetingId, UUID actionId) {
        requireMeeting(tenant, meetingId);
        actionRepository.delete(requireAction(tenant, meetingId, actionId));
    }

    // ──────────────────────────── Decisions ────────────────────────────

    @Transactional(readOnly = true)
    public List<MeetingDecisionResponse> listDecisions(AdminTenantContext tenant, UUID meetingId) {
        requireMeeting(tenant, meetingId);
        return decisionRepository.findByMeetingIdVisibleToOrg(meetingId, tenant.tenantId())
                .stream().map(this::toDecisionResponse).toList();
    }

    @Transactional(readOnly = true)
    public MeetingDecisionResponse getDecision(AdminTenantContext tenant, UUID meetingId, UUID decisionId) {
        requireMeeting(tenant, meetingId);
        return toDecisionResponse(requireDecision(tenant, meetingId, decisionId));
    }

    @Transactional
    public MeetingDecisionResponse createDecision(
            AdminTenantContext tenant, UUID meetingId, MeetingDecisionCreateRequest request) {
        Meeting meeting = requireMeeting(tenant, meetingId);
        MeetingDecision decision = new MeetingDecision();
        decision.setMeetingId(meeting.getId());
        decision.setTenantId(tenant.tenantId());
        decision.setOrgId(tenant.tenantId()); // canonical writer sets BOTH columns (trigger is backstop)
        decision.setTitle(request.title());
        decision.setDetail(request.detail());
        decision.setDecidedBySubject(request.decidedBySubject());
        decision.setDecidedAt(request.decidedAt());
        decision.setCreatedBySubject(tenant.subject());
        decision.setLastUpdatedBySubject(tenant.subject());
        return toDecisionResponse(decisionRepository.save(decision));
    }

    @Transactional
    public MeetingDecisionResponse updateDecision(
            AdminTenantContext tenant, UUID meetingId, UUID decisionId, MeetingDecisionUpdateRequest request) {
        requireMeeting(tenant, meetingId);
        MeetingDecision decision = requireDecision(tenant, meetingId, decisionId);
        requireExpectedVersion(request.expectedVersion(), decision.getVersion());
        decision.setTitle(request.title());
        decision.setDetail(request.detail());
        decision.setDecidedBySubject(request.decidedBySubject());
        decision.setDecidedAt(request.decidedAt());
        decision.setLastUpdatedBySubject(tenant.subject());
        return toDecisionResponse(decisionRepository.save(decision));
    }

    @Transactional
    public void deleteDecision(AdminTenantContext tenant, UUID meetingId, UUID decisionId) {
        requireMeeting(tenant, meetingId);
        decisionRepository.delete(requireDecision(tenant, meetingId, decisionId));
    }

    // ──────────────────────── Analysis result (read-only, #244 BE-1b) ────────────────────────
    //
    // The write side (BE-1, MeetingAnalysisIngestionService) is internal
    // service-token-only; this read side is the normal admin/desktop JWT
    // path (AdminTenantContext, org-scoped via requireMeeting), same as
    // sessions/actions/decisions above. Decisions/actions for this run are
    // NOT duplicated here — callers already have listActions/listDecisions
    // and can filter by analysisRunId if they want only the automated rows.

    @Transactional(readOnly = true)
    public MeetingAnalysisResultResponse getAnalysisResult(AdminTenantContext tenant, UUID meetingId) {
        requireMeeting(tenant, meetingId);
        MeetingAnalysisRun run = analysisRunRepository
                .findByMeetingIdAndStatus(meetingId, MeetingAnalysisRunStatus.CANONICAL)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No analysis result for this meeting yet."));
        MeetingSummary summary = summaryRepository.findByAnalysisRunId(run.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Canonical analysis run has no summary row (data integrity)."));
        return toAnalysisResultResponse(run, summary);
    }

    private MeetingAnalysisResultResponse toAnalysisResultResponse(
            MeetingAnalysisRun run, MeetingSummary summary) {
        return new MeetingAnalysisResultResponse(
                run.getMeetingId(),
                run.getId(),
                run.getStatus().name(),
                summary.getSummaryText(),
                summary.getGroundingStatus(),
                run.getAnalyzerContractVersion(),
                run.getModelVersion(),
                run.getPromptVersion(),
                run.getGeneratedAt());
    }

    // ──────────────────────── Resolve-or-404 helpers ────────────────────────

    private Meeting requireMeeting(AdminTenantContext tenant, UUID id) {
        return meetingRepository.findVisibleToOrgAndId(tenant.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found."));
    }

    private MeetingSession requireSession(AdminTenantContext tenant, UUID meetingId, UUID sessionId) {
        return sessionRepository.findByIdAndMeetingIdVisibleToOrg(sessionId, meetingId, tenant.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting session not found."));
    }

    private MeetingAction requireAction(AdminTenantContext tenant, UUID meetingId, UUID actionId) {
        return actionRepository.findByIdAndMeetingIdVisibleToOrg(actionId, meetingId, tenant.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting action not found."));
    }

    private MeetingDecision requireDecision(AdminTenantContext tenant, UUID meetingId, UUID decisionId) {
        return decisionRepository.findByIdAndMeetingIdVisibleToOrg(decisionId, meetingId, tenant.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting decision not found."));
    }

    /**
     * Optimistic-lock precondition (stale-write guard). When the caller
     * supplies {@code expectedVersion}, reject the update with
     * {@link OptimisticLockingFailureException} (→ 409 via the global handler)
     * if it does not match the currently-persisted {@code @Version}. Skipped
     * when {@code expectedVersion} is null (backward-compat last-writer-wins).
     * Fires BEFORE any mutation so a mismatched write never touches the entity.
     */
    private static void requireExpectedVersion(Long expectedVersion, Long currentVersion) {
        if (expectedVersion != null && !expectedVersion.equals(currentVersion)) {
            throw new OptimisticLockingFailureException(
                    "Stale update: expectedVersion=" + expectedVersion + " currentVersion=" + currentVersion);
        }
    }

    // ─────────────────────────── OpenFGA tuple write ───────────────────────────

    /**
     * Writes the {@code owner @ meeting:<id>} tuple for the creator.
     *
     * <p><b>Fail-closed when enabled.</b> No-op only when no OpenFGA bean is
     * present (local/dev/test/integration) — the disabled-but-present case is
     * also a no-op inside the SDK ({@code !enabled → return}). When OpenFGA is
     * enabled and the write genuinely fails the {@link OpenFgaAuthzService}
     * throws a {@link RuntimeException}, which is allowed to propagate so the
     * surrounding {@code @Transactional createMeeting} ROLLS BACK — the meeting
     * row and its owner tuple are committed atomically (no orphan meeting with
     * a missing owner grant). An idempotent re-write of an already-existing
     * tuple does NOT throw (SDK swallows it), so it never spuriously rolls back.
     */
    private void writeOwnerTuple(String subject, UUID meetingId) {
        OpenFgaAuthzService authz = authzServiceProvider.getIfAvailable();
        if (authz == null || !authz.isEnabled()) {
            return;
        }
        // Let a genuine write failure (RuntimeException) propagate → rollback.
        authz.writeTuple(subject, MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, meetingId.toString());
    }

    // ─────────────────────────── Response mappers ───────────────────────────

    private MeetingResponse toMeetingResponse(Meeting m) {
        return new MeetingResponse(
                m.getId(),
                m.getEffectiveOrgId(),
                m.getTitle(),
                m.getDescription(),
                m.getStatus(),
                m.getScheduledStart(),
                m.getScheduledEnd(),
                m.getOrganizerSubject(),
                m.getCreatedBySubject(),
                m.getCreatedAt(),
                m.getLastUpdatedBySubject(),
                m.getUpdatedAt(),
                m.getVersion());
    }

    private MeetingSessionResponse toSessionResponse(MeetingSession s) {
        return new MeetingSessionResponse(
                s.getId(),
                s.getMeetingId(),
                s.getEffectiveOrgId(),
                s.getSessionLabel(),
                s.getStartedAt(),
                s.getEndedAt(),
                s.getRecordingUri(),
                s.getTranscriptStatus(),
                s.getCreatedBySubject(),
                s.getCreatedAt(),
                s.getLastUpdatedBySubject(),
                s.getUpdatedAt(),
                s.getVersion());
    }

    private MeetingActionResponse toActionResponse(MeetingAction a) {
        return new MeetingActionResponse(
                a.getId(),
                a.getMeetingId(),
                a.getEffectiveOrgId(),
                a.getDescription(),
                a.getAssigneeSubject(),
                a.getStatus(),
                a.getDueAt(),
                a.getCreatedBySubject(),
                a.getCreatedAt(),
                a.getLastUpdatedBySubject(),
                a.getUpdatedAt(),
                a.getVersion());
    }

    private MeetingDecisionResponse toDecisionResponse(MeetingDecision d) {
        return new MeetingDecisionResponse(
                d.getId(),
                d.getMeetingId(),
                d.getEffectiveOrgId(),
                d.getTitle(),
                d.getDetail(),
                d.getDecidedBySubject(),
                d.getDecidedAt(),
                d.getCreatedBySubject(),
                d.getCreatedAt(),
                d.getLastUpdatedBySubject(),
                d.getUpdatedAt(),
                d.getVersion());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String toUserPrincipalRef(String principal) {
        if (principal == null || principal.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Meeting recording access denied.");
        }
        return principal.startsWith("user:") ? principal : "user:" + principal;
    }
}
