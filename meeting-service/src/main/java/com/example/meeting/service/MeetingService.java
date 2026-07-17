package com.example.meeting.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.MeetingActionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingActionResponse;
import com.example.meeting.dto.v1.admin.MeetingActionUpdateRequest;
import com.example.meeting.dto.v1.admin.MeetingCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingDecisionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingDecisionResponse;
import com.example.meeting.dto.v1.admin.MeetingDecisionUpdateRequest;
import com.example.meeting.dto.v1.admin.MeetingResponse;
import com.example.meeting.dto.v1.admin.MeetingSessionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingSessionResponse;
import com.example.meeting.dto.v1.admin.MeetingSessionUpdateRequest;
import com.example.meeting.dto.v1.admin.MeetingUpdateRequest;
import com.example.meeting.dto.v1.admin.RecordingLifecycleResponse;
import com.example.meeting.dto.v1.admin.RecordingLifecycleSyncRequest;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingActionStatus;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingSession;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.model.TranscriptStatus;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
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
 * {@code owner @ meeting:&lt;id&gt;} tuple binding the creator's stable OIDC
 * subject (ADR-0012-EA:
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
    private final ObjectProvider<OpenFgaAuthzService> authzServiceProvider;
    private final boolean legacyUserIdFallbackEnabled;
    private final boolean legacyUserIdDualWriteEnabled;

    public MeetingService(
            MeetingRepository meetingRepository,
            MeetingSessionRepository sessionRepository,
            MeetingActionRepository actionRepository,
            MeetingDecisionRepository decisionRepository,
            ObjectProvider<OpenFgaAuthzService> authzServiceProvider,
            @Value("${meeting.authz.object-principal.legacy-user-id-fallback-enabled:false}")
            boolean legacyUserIdFallbackEnabled,
            @Value("${meeting.authz.object-principal.legacy-user-id-dual-write-enabled:false}")
            boolean legacyUserIdDualWriteEnabled) {
        this.meetingRepository = meetingRepository;
        this.sessionRepository = sessionRepository;
        this.actionRepository = actionRepository;
        this.decisionRepository = decisionRepository;
        this.authzServiceProvider = authzServiceProvider;
        this.legacyUserIdFallbackEnabled = legacyUserIdFallbackEnabled;
        this.legacyUserIdDualWriteEnabled = legacyUserIdDualWriteEnabled;
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
        Meeting meeting = requireMeeting(tenant, id);
        requireRecordingAccess(tenant, id, meeting);
    }

    private void requireRecordingAccess(AdminTenantContext tenant, UUID id, Meeting meeting) {
        String stablePrincipalRef = toUserPrincipalRef(tenant.subject());

        OpenFgaAuthzService authz = authzServiceProvider.getIfAvailable();
        if (authz == null || !authz.isEnabled()) {
            return;
        }

        boolean allowed = authz.checkPrincipal(
                stablePrincipalRef,
                MeetingAuthz.CAN_RECORD,
                MeetingAuthz.OBJECT_TYPE,
                id.toString());
        if (allowed) {
            return;
        }

        if (legacyUserIdFallbackEnabled
                && tenant.subject().equals(meeting.getCreatedBySubject())) {
            // The legacy userId is accepted only as an issuer-signed compatibility
            // claim for the same stable OIDC subject that created this meeting.
            String legacyPrincipalRef = toUserPrincipalRef(tenant.authzPrincipal());
            if (!legacyPrincipalRef.equals(stablePrincipalRef)) {
                boolean stablePrincipalBlocked = authz.checkPrincipal(
                        stablePrincipalRef,
                        MeetingAuthz.BLOCKED,
                        MeetingAuthz.OBJECT_TYPE,
                        id.toString());
                if (!stablePrincipalBlocked && authz.checkPrincipal(
                        legacyPrincipalRef,
                        MeetingAuthz.CAN_RECORD,
                        MeetingAuthz.OBJECT_TYPE,
                        id.toString())) {
                    return;
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Meeting recording access denied.");
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
        // Object ownership uses the immutable OIDC subject. Numeric userId is
        // a module-authorization compatibility alias, not durable object identity.
        writeOwnerTuple(tenant.subject(), saved.getId());
        if (legacyUserIdDualWriteEnabled
                && !toUserPrincipalRef(tenant.subject()).equals(toUserPrincipalRef(tenant.authzPrincipal()))) {
            try {
                writeOwnerTuple(tenant.authzPrincipal(), saved.getId());
            } catch (RuntimeException legacyWriteFailure) {
                try {
                    deleteOwnerTuple(tenant.subject(), saved.getId());
                } catch (RuntimeException compensationFailure) {
                    legacyWriteFailure.addSuppressed(compensationFailure);
                }
                throw legacyWriteFailure;
            }
        }
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
    public RecordingLifecycleResponse syncRecordingLifecycle(
            AdminTenantContext tenant, UUID meetingId, RecordingLifecycleSyncRequest request) {
        Meeting meeting = requireMeetingForUpdate(tenant, meetingId);
        requireRecordingAccess(tenant, meetingId, meeting);
        if (meeting.getStatus() == MeetingStatus.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cancelled meeting cannot be recorded.");
        }

        Optional<MeetingSession> existingSession = sessionRepository.findByExternalSessionIdVisibleToOrg(
                meetingId, request.externalSessionId(), tenant.tenantId());
        MeetingSession session = existingSession
                .orElseGet(() -> newRecordingSession(tenant, meeting, request));
        boolean sessionChanged = existingSession.isEmpty();

        if (session.getStartedAt() == null) {
            session.setStartedAt(request.startedAt());
            sessionChanged = true;
        }
        if (request.endedAt() != null
                && session.getStartedAt() != null
                && request.endedAt().isBefore(session.getStartedAt())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Recording end cannot precede the canonical start.");
        }
        if (request.endedAt() != null && session.getEndedAt() == null) {
            session.setEndedAt(request.endedAt());
            sessionChanged = true;
        }
        if (request.endedAt() != null && session.getTranscriptStatus() == TranscriptStatus.PENDING) {
            session.setTranscriptStatus(TranscriptStatus.PROCESSING);
            sessionChanged = true;
        }
        MeetingSession savedSession = session;
        if (sessionChanged) {
            session.setLastUpdatedBySubject(tenant.subject());
            savedSession = sessionRepository.saveAndFlush(session);
        }

        boolean currentSessionActive = savedSession.getEndedAt() == null;
        MeetingStatus desiredMeetingStatus;
        if (currentSessionActive) {
            desiredMeetingStatus = MeetingStatus.IN_PROGRESS;
        } else {
            UUID savedSessionId = savedSession.getId();
            boolean anotherSessionActive = sessionRepository
                    .findByMeetingIdVisibleToOrg(meetingId, tenant.tenantId())
                    .stream()
                    .anyMatch(candidate -> !candidate.getId().equals(savedSessionId)
                            && candidate.getStartedAt() != null
                            && candidate.getEndedAt() == null);
            desiredMeetingStatus = anotherSessionActive ? MeetingStatus.IN_PROGRESS : MeetingStatus.COMPLETED;
        }
        Meeting savedMeeting = meeting;
        if (meeting.getStatus() != desiredMeetingStatus) {
            meeting.setStatus(desiredMeetingStatus);
            meeting.setLastUpdatedBySubject(tenant.subject());
            savedMeeting = meetingRepository.saveAndFlush(meeting);
        }

        return new RecordingLifecycleResponse(
                savedMeeting.getId(),
                savedSession.getId(),
                savedSession.getExternalSessionId(),
                savedMeeting.getStatus(),
                savedSession.getTranscriptStatus(),
                savedSession.getStartedAt(),
                savedSession.getEndedAt());
    }

    private MeetingSession newRecordingSession(
            AdminTenantContext tenant, Meeting meeting, RecordingLifecycleSyncRequest request) {
        MeetingSession session = new MeetingSession();
        session.setMeetingId(meeting.getId());
        session.setTenantId(tenant.tenantId());
        session.setOrgId(tenant.tenantId());
        session.setSessionLabel(request.externalSessionId());
        session.setExternalSessionId(request.externalSessionId());
        session.setStartedAt(request.startedAt());
        session.setTranscriptStatus(TranscriptStatus.PENDING);
        session.setCreatedBySubject(tenant.subject());
        session.setLastUpdatedBySubject(tenant.subject());
        return session;
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

    // ──────────────────────── Resolve-or-404 helpers ────────────────────────

    private Meeting requireMeeting(AdminTenantContext tenant, UUID id) {
        return meetingRepository.findVisibleToOrgAndId(tenant.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting not found."));
    }

    private Meeting requireMeetingForUpdate(AdminTenantContext tenant, UUID id) {
        return meetingRepository.findVisibleToOrgAndIdForUpdate(tenant.tenantId(), id)
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
     * surrounding {@code @Transactional createMeeting} rolls back the meeting
     * row. OpenFGA is an external store, so the dual-write path attempts a
     * compensating delete if its second write fails. A failed compensation is
     * attached as a suppressed exception and must be reconciled by the bounded
     * migration runbook. An idempotent re-write of an already-existing tuple
     * does not throw.
     */
    private void writeOwnerTuple(String subject, UUID meetingId) {
        OpenFgaAuthzService authz = authzServiceProvider.getIfAvailable();
        if (authz == null || !authz.isEnabled()) {
            return;
        }
        // Let a genuine write failure (RuntimeException) propagate → rollback.
        authz.writeTuple(toOpenFgaUserId(subject), MeetingAuthz.OWNER,
                MeetingAuthz.OBJECT_TYPE, meetingId.toString());
    }

    private void deleteOwnerTuple(String subject, UUID meetingId) {
        OpenFgaAuthzService authz = authzServiceProvider.getIfAvailable();
        if (authz == null || !authz.isEnabled()) {
            return;
        }
        authz.deleteTuple(toOpenFgaUserId(subject), MeetingAuthz.OWNER,
                MeetingAuthz.OBJECT_TYPE, meetingId.toString());
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

    private static String toOpenFgaUserId(String principal) {
        String principalRef = toUserPrincipalRef(principal);
        return principalRef.substring("user:".length());
    }
}
