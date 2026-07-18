package com.example.meeting.service;

import com.example.meeting.dto.v1.internal.MeetingAnalysisActionIngest;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestRequest;
import com.example.meeting.events.MeetingEventOutboxFactory;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingActionStatus;
import com.example.meeting.model.MeetingAnalysisJobCapabilityUse;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingEventOutbox;
import com.example.meeting.model.MeetingItemSource;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAnalysisJobCapabilityUseRepository;
import com.example.meeting.repository.MeetingAnalysisRunDestructionTombstoneRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionErasureRepository;
import com.example.meeting.security.AnalysisJobCapabilityVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The transactional half of analysis-result ingestion — Faz 24
 * (platform-ai#244 BE-1c). Deliberately a SEPARATE bean from the
 * {@link MeetingAnalysisResultIngestionService} orchestrator so the
 * transaction boundary is a real proxy boundary (no self-invocation), and so
 * the post-failure winner lookup runs in a genuinely fresh transaction after
 * a failed write has fully unwound.
 *
 * <p>{@link #insertNewRun} is the ONE atomic write: the run row, every AI child
 * (decisions + actions) AND the BE-1d transactional-outbox rows commit or roll
 * back together. A mid-write child constraint violation aborts the whole unit, so
 * a run row never lands without its children — and no outbox event is ever emitted
 * for a run that did not commit.
 */
@Component
public class MeetingAnalysisResultWriter {

    /**
     * Audit subject stamped on AI-authored child rows (the {@code created_by} /
     * {@code last_updated_by} columns are NOT NULL). It is provenance, not a human
     * identity — {@code source=AI_ANALYSIS} + {@code decided_by_subject=NULL} already
     * mark a decision as machine-extracted and human-unverified. Constant on purpose:
     * it is not part of the idempotency payload hash.
     */
    static final String AI_SUBJECT = "system:meeting-ai";

    private static final int DECISION_TITLE_MAX_CODE_POINTS = 512;

    private final MeetingAnalysisRunRepository runRepository;
    private final MeetingAnalysisRunDestructionTombstoneRepository destructionTombstones;
    private final MeetingAnalysisJobCapabilityUseRepository capabilityUseRepository;
    private final MeetingActionRepository actionRepository;
    private final MeetingDecisionRepository decisionRepository;
    private final MeetingEventOutboxRepository outboxRepository;
    private final MeetingRepository meetingRepository;
    private final MeetingSessionErasureRepository sessionErasureRepository;
    private final ObjectMapper objectMapper;
    private final MeetingEventOutboxFactory eventFactory;

    public MeetingAnalysisResultWriter(
            MeetingAnalysisRunRepository runRepository,
            MeetingAnalysisRunDestructionTombstoneRepository destructionTombstones,
            MeetingAnalysisJobCapabilityUseRepository capabilityUseRepository,
            MeetingActionRepository actionRepository,
            MeetingDecisionRepository decisionRepository,
            MeetingEventOutboxRepository outboxRepository,
            MeetingRepository meetingRepository,
            MeetingSessionErasureRepository sessionErasureRepository,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.destructionTombstones = destructionTombstones;
        this.capabilityUseRepository = capabilityUseRepository;
        this.actionRepository = actionRepository;
        this.decisionRepository = decisionRepository;
        this.outboxRepository = outboxRepository;
        this.meetingRepository = meetingRepository;
        this.sessionErasureRepository = sessionErasureRepository;
        this.objectMapper = objectMapper;
        // Plain collaborator (not a Spring bean) — shares the same ObjectMapper.
        this.eventFactory = new MeetingEventOutboxFactory(objectMapper);
    }

    /**
     * Atomically persist a new analysis run and its AI children. The caller has
     * already resolved the meeting, computed {@code payloadHash} and confirmed no
     * run currently exists for {@code analysisRunId}.
     *
     * <p>The run is {@code saveAndFlush}ed first so a concurrent same-key insert
     * surfaces its primary-key collision here (inside this transaction); the
     * children AND the BE-1d outbox rows are then saved and an explicit final
     * {@code flush} forces any constraint violation to abort THIS transaction
     * rather than surfacing only at commit — either way the whole unit (run +
     * children + outbox) rolls back.
     *
     * @return the persisted run (managed within this transaction)
     */
    @Transactional
    public MeetingAnalysisRun insertNewRun(
            Meeting meeting,
            UUID analysisRunId,
            String payloadHash,
            MeetingAnalysisResultIngestRequest request,
            AnalysisJobCapabilityVerifier.JobBinding binding) {

        UUID meetingId = meeting.getId();
        UUID tenantId = meeting.getTenantId();
        UUID orgId = meeting.getEffectiveOrgId();

        // Serialize occurrence admission per meeting. Delivery time and model
        // generation time are not transcript occurrence order.
        meetingRepository.findVisibleToOrgAndIdForUpdate(orgId, meetingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND"));
        rejectErasedSession(tenantId, meetingId, binding.sessionId());
        rejectDestroyedRunId(analysisRunId);
        runRepository.findLatestCanonicalOccurrence(meetingId, orgId).ifPresent(latest -> {
            boolean olderTime = request.finalizedAt().isBefore(latest.getFinalizedAt());
            boolean olderVersionAtSameTime = request.finalizedAt().equals(latest.getFinalizedAt())
                    && request.finalizationVersion() < latest.getFinalizationVersion();
            if (olderTime || olderVersionAtSameTime) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "STALE_FINALIZATION");
            }
        });

        MeetingAnalysisRun run = new MeetingAnalysisRun();
        run.setAnalysisRunId(analysisRunId);
        run.setMeetingId(meetingId);
        run.setTenantId(tenantId);
        run.setOrgId(orgId); // canonical writer sets BOTH columns (DB trigger is the backstop)
        run.setTranscriptSessionId(request.transcriptSessionId());
        run.setTranscriptSha256(request.transcriptSha256());
        run.setFinalizationVersion(request.finalizationVersion());
        run.setFinalizedAt(request.finalizedAt());
        run.setAnalysisSpecVersion(request.analysisSpecVersion());
        run.setJobCapabilityId(binding.capabilityId());
        run.setAnalyzerContractVersion(request.analyzerContractVersion());
        run.setModel(request.model());
        run.setBackend(request.backend());
        run.setPromptVersion(request.promptVersion());
        run.setPayloadHash(payloadHash);
        run.setSummary(request.summary());
        run.setSummaryGroundingStatus(request.summaryGroundingStatus());
        run.setSummaryCitations(toJson(request.summaryCitations()));
        run.setCitations(toJson(request.citations()));
        run.setRejectedClaims(toJson(request.rejectedClaims()));
        run.setUngroundedCount(request.ungroundedCount() == null ? 0 : request.ungroundedCount());
        run.setRedacted(request.redacted() != null && request.redacted());
        run.setRedactionCount(request.redactionCount() == null ? 0 : request.redactionCount());
        run.setSupersedesAnalysisRunId(request.supersedesAnalysisRunId());
        run.setGeneratedAt(request.generatedAt());
        runRepository.saveAndFlush(run);
        capabilityUseRepository.saveAndFlush(capabilityUse(analysisRunId, binding));

        List<MeetingDecision> savedDecisions = new ArrayList<>();
        int ordinal = 0;
        for (String decisionText : request.decisions()) {
            MeetingDecision decision = new MeetingDecision();
            decision.setMeetingId(meetingId);
            decision.setTenantId(tenantId);
            decision.setOrgId(orgId);
            // meeting-ai returns decisions as plain strings: title = Unicode-safe first
            // 512 code points, detail = the full text, decided_by_subject = NULL,
            // decided_at = the run's generated_at.
            decision.setTitle(truncateToCodePoints(decisionText, DECISION_TITLE_MAX_CODE_POINTS));
            decision.setDetail(decisionText);
            decision.setDecidedBySubject(null);
            decision.setDecidedAt(request.generatedAt());
            decision.setSource(MeetingItemSource.AI_ANALYSIS);
            decision.setAnalysisRunId(analysisRunId);
            decision.setOrdinal(ordinal++);
            decision.setCreatedBySubject(AI_SUBJECT);
            decision.setLastUpdatedBySubject(AI_SUBJECT);
            decisionRepository.save(decision);
            savedDecisions.add(decision);
        }

        List<MeetingAction> savedActions = new ArrayList<>();
        int actionOrdinal = 0;
        for (MeetingAnalysisActionIngest actionIn : request.actions()) {
            MeetingAction action = new MeetingAction();
            action.setMeetingId(meetingId);
            action.setTenantId(tenantId);
            action.setOrgId(orgId);
            action.setDescription(actionIn.text());
            action.setAssigneeSubject(actionIn.assignee());
            action.setStatus(MeetingActionStatus.OPEN);
            action.setDueAt(actionIn.due());
            action.setSource(MeetingItemSource.AI_ANALYSIS);
            action.setAnalysisRunId(analysisRunId);
            action.setOrdinal(actionOrdinal++);
            action.setCreatedBySubject(AI_SUBJECT);
            action.setLastUpdatedBySubject(AI_SUBJECT);
            actionRepository.save(action);
            savedActions.add(action);
        }

        // BE-1d transactional outbox: the #412 domain events (meeting.summary.ready +
        // one meeting.action.assigned per non-null-assignee action) are written in
        // THIS same transaction. They therefore commit atomically with the run +
        // children — publication is deferred to a poller that reads only committed
        // rows (commit-after-emit). A NULL-assignee action yields no event.
        for (MeetingEventOutbox event : eventFactory.build(run, savedDecisions, savedActions)) {
            outboxRepository.save(event);
        }

        // Force the children + outbox rows to flush now: any constraint violation must
        // abort THIS transaction (atomicity), not surface only at commit-time translation.
        runRepository.flush();
        return run;
    }

    /** Fresh-transaction global lookup used by the orchestrator's post-failure reconciliation. */
    @Transactional(readOnly = true)
    public Optional<MeetingAnalysisRun> findCommittedRun(UUID analysisRunId) {
        return runRepository.findById(analysisRunId);
    }

    /** Persist one fresh capability used for a successful idempotent retry. */
    @Transactional
    public void consumeRetryCapability(
            UUID analysisRunId,
            AnalysisJobCapabilityVerifier.JobBinding binding) {
        meetingRepository.findVisibleToOrgAndIdForUpdate(binding.tenantId(), binding.meetingId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND"));
        rejectErasedSession(binding.tenantId(), binding.meetingId(), binding.sessionId());
        rejectDestroyedRunId(analysisRunId);
        capabilityUseRepository.saveAndFlush(capabilityUse(analysisRunId, binding));
    }

    private void rejectErasedSession(UUID tenantId, UUID meetingId, UUID sessionId) {
        if (sessionErasureRepository.existsByTenantIdAndMeetingIdAndSessionId(
                tenantId, meetingId, sessionId)) {
            throw new ResponseStatusException(HttpStatus.GONE, "MEETING_SESSION_ERASED");
        }
    }

    private void rejectDestroyedRunId(UUID analysisRunId) {
        if (destructionTombstones.existsById(analysisRunId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
        }
    }

    private static MeetingAnalysisJobCapabilityUse capabilityUse(
            UUID analysisRunId,
            AnalysisJobCapabilityVerifier.JobBinding binding) {
        MeetingAnalysisJobCapabilityUse use = new MeetingAnalysisJobCapabilityUse();
        use.setCapabilityId(binding.capabilityId());
        use.setAnalysisRunId(analysisRunId);
        use.setExpiresAt(binding.expiresAt());
        return use;
    }

    private String toJson(List<?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise analysis JSON column.", e);
        }
    }

    /**
     * Truncate to at most {@code maxCodePoints} Unicode code points on a code-point
     * boundary — never splits a surrogate pair (so an astral character, e.g. an emoji,
     * is kept whole or dropped whole). ≤ {@code maxCodePoints} code points always fits
     * a {@code VARCHAR(maxCodePoints)} column, which Postgres measures in code points.
     */
    static String truncateToCodePoints(String value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        int offset = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, offset);
    }
}
