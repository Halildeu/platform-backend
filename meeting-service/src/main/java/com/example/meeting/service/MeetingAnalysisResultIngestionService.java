package com.example.meeting.service;

import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestRequest;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestResponse;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.security.AnalysisJobCapabilityVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates atomic ingestion of a completed meeting-ai analysis result into
 * the system of record — Faz 24 (platform-ai#244 BE-1c). This bean is
 * intentionally NOT {@code @Transactional}: it owns the idempotency /
 * conflict / race decisions around {@link MeetingAnalysisResultWriter}, whose
 * separate transactional methods give it real transaction boundaries.
 *
 * <h2>Idempotency &amp; conflict (global namespace)</h2>
 * {@code analysisRunId} (the {@code Idempotency-Key}) is the run table's GLOBAL
 * primary key, so idempotency is resolved by a GLOBAL {@code findById} — matching
 * the DB semantics exactly. A found run is a 200 <em>replay</em> ONLY when its
 * tenant, meeting AND {@code payloadHash} all match; every other case (different
 * payload, or the same key reused for a different meeting/tenant) is a generic
 * {@code 409 IDEMPOTENCY_CONFLICT} that discloses nothing about the other row.
 *
 * <h2>Retention-bounded</h2>
 * The idempotency guarantee is bounded by run retention: a KVKK/retention purge
 * of a run frees its key, after which the same {@code analysisRunId} may be
 * accepted as a new run. A permanent tombstone ledger is out of scope for BE-1c.
 *
 * <h2>Concurrency</h2>
 * Two racing inserts of the same key both pass the pre-check, then collide on the
 * PK; the loser catches {@link DataIntegrityViolationException} and, in a fresh
 * transaction, reconciles to whatever aggregate is now committed (200/409) — so
 * exactly one row exists and the loser gets a deterministic idempotent result,
 * never a 500. If no run is committed for the key, the failure was not the PK, so
 * a precondition that could have raced away (the meeting hard-deleted, the
 * supersedes target purged) is re-checked for a deterministic 4xx; only a genuine
 * persistence failure is rethrown.
 *
 * <h2>Tenant</h2>
 * The job capability carries the tenant selected by transcript-service, while
 * meeting-service independently derives the canonical tenant from the
 * {@code {meetingId}} row. Ingestion requires both values to match; the body has
 * no tenant field and is never trusted as tenant authority.
 */
@Service
public class MeetingAnalysisResultIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MeetingAnalysisResultIngestionService.class);

    private final MeetingRepository meetingRepository;
    private final MeetingAnalysisRunRepository runRepository;
    private final MeetingAnalysisResultWriter writer;
    private final MeetingAnalysisPayloadHasher hasher;
    private final AnalysisJobCapabilityVerifier capabilityVerifier;

    public MeetingAnalysisResultIngestionService(
            MeetingRepository meetingRepository,
            MeetingAnalysisRunRepository runRepository,
            MeetingAnalysisResultWriter writer,
            MeetingAnalysisPayloadHasher hasher,
            AnalysisJobCapabilityVerifier capabilityVerifier) {
        this.meetingRepository = meetingRepository;
        this.runRepository = runRepository;
        this.writer = writer;
        this.hasher = hasher;
        this.capabilityVerifier = capabilityVerifier;
    }

    public MeetingAnalysisResultIngestResponse ingest(
            UUID meetingId,
            UUID analysisRunId,
            String jobCapability,
            MeetingAnalysisResultIngestRequest request) {

        // Defence-in-depth: an optional body meetingId must not contradict the path.
        if (request.meetingId() != null && !meetingId.equals(request.meetingId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MEETING_ID_MISMATCH");
        }

        // Tenant authority is the meeting row, not the payload. 404 (no existence leak) if absent.
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND"));
        UUID tenantId = meeting.getTenantId();

        AnalysisJobCapabilityVerifier.JobBinding binding = capabilityVerifier.verify(jobCapability);
        UUID transcriptSessionId;
        try {
            transcriptSessionId = UUID.fromString(request.transcriptSessionId());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRANSCRIPT_SESSION_ID_INVALID");
        }
        boolean exactBinding = binding.tenantId().equals(tenantId)
                && binding.meetingId().equals(meetingId)
                && binding.sessionId().equals(transcriptSessionId)
                && binding.finalizationVersion() == request.finalizationVersion()
                && binding.finalizedAt().equals(request.finalizedAt())
                && binding.transcriptSha256().equals(request.transcriptSha256())
                && binding.analysisRunId().equals(analysisRunId)
                && binding.analysisSpecVersion().equals(request.analysisSpecVersion());
        if (!exactBinding) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "JOB_CAPABILITY_BINDING_MISMATCH");
        }
        String payloadHash = hasher.hash(meetingId, tenantId, analysisRunId, request);

        // Global idempotency pre-check (matches the global PK).
        Optional<MeetingAnalysisRun> existing = runRepository.findById(analysisRunId);
        if (existing.isPresent()) {
            return reconcile(existing.get(), meetingId, tenantId, payloadHash, request, binding);
        }

        validateSupersedes(request.supersedesAnalysisRunId(), analysisRunId, meetingId, tenantId);

        try {
            MeetingAnalysisRun saved = writer.insertNewRun(
                    meeting, analysisRunId, payloadHash, request, binding);
            return response(saved, request, false);
        } catch (DataIntegrityViolationException ex) {
            return reconcileAfterFailure(
                    ex, meetingId, analysisRunId, tenantId, payloadHash, request, binding);
        }
    }

    /**
     * Reconcile against an already-committed run for the same global key: a 200
     * replay iff it is the exact same target and payload, otherwise a generic 409.
     */
    private MeetingAnalysisResultIngestResponse reconcile(
            MeetingAnalysisRun run,
            UUID meetingId,
            UUID tenantId,
            String payloadHash,
            MeetingAnalysisResultIngestRequest request,
            AnalysisJobCapabilityVerifier.JobBinding binding) {
        boolean sameTarget = run.getMeetingId().equals(meetingId)
                && run.getTenantId().equals(tenantId)
                && run.getPayloadHash().equals(payloadHash);
        if (sameTarget) {
            if (binding.capabilityId().equals(run.getJobCapabilityId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "JOB_CAPABILITY_REPLAY");
            }
            try {
                writer.consumeRetryCapability(run.getAnalysisRunId(), binding);
            } catch (DataIntegrityViolationException ex) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "JOB_CAPABILITY_REPLAY");
            }
            return response(run, request, true);
        }
        // Generic: never disclose that the key belongs to a different meeting/tenant.
        throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
    }

    /**
     * Post-failure OUTCOME reconciliation (not a PK-cause classification). The
     * failed write transaction has fully unwound; look up the committed aggregate
     * in a fresh transaction and reconcile to it. If none exists, the failure was
     * not the run PK — re-check a precondition that could have raced away for a
     * deterministic 4xx, else preserve the original persistence failure.
     */
    private MeetingAnalysisResultIngestResponse reconcileAfterFailure(
            DataIntegrityViolationException ex,
            UUID meetingId,
            UUID analysisRunId,
            UUID tenantId,
            String payloadHash,
            MeetingAnalysisResultIngestRequest request,
            AnalysisJobCapabilityVerifier.JobBinding binding) {

        Optional<MeetingAnalysisRun> committed = writer.findCommittedRun(analysisRunId);
        if (committed.isPresent()) {
            // Safe operational telemetry only — never the transcript/summary/citations/token.
            log.warn("analysis-result ingestion reconciled after tx failure "
                            + "reconciledAfterTransactionFailure=true cause={}",
                    ex.getClass().getSimpleName());
            return reconcile(committed.get(), meetingId, tenantId, payloadHash, request, binding);
        }

        // No committed run ⇒ not the PK. Re-validate raced-away preconditions.
        if (meetingRepository.findById(meetingId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND");
        }
        if (request.supersedesAnalysisRunId() != null) {
            // Throws a deterministic 422 if the supersedes target was purged mid-write.
            validateSupersedes(request.supersedesAnalysisRunId(), analysisRunId, meetingId, tenantId);
        }
        // A genuine persistence failure (e.g. a child that violated a column bound).
        throw ex;
    }

    /**
     * Pre-validate an explicit supersession link: it must not be the run itself and
     * must reference an existing run in the SAME tenant and meeting. Any failure is a
     * generic 422 (no disclosure of another tenant/meeting). The composite FK in V3 is
     * the final authority; this turns its would-be 500 into a clean 4xx.
     */
    private void validateSupersedes(UUID supersedesAnalysisRunId, UUID analysisRunId, UUID meetingId, UUID tenantId) {
        if (supersedesAnalysisRunId == null) {
            return;
        }
        if (supersedesAnalysisRunId.equals(analysisRunId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SUPERSEDES_RUN_INVALID");
        }
        MeetingAnalysisRun target = runRepository.findById(supersedesAnalysisRunId).orElse(null);
        if (target == null
                || !target.getTenantId().equals(tenantId)
                || !target.getMeetingId().equals(meetingId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "SUPERSEDES_RUN_NOT_FOUND");
        }
    }

    /**
     * Build the response. Child counts come from the (validated, null-normalised)
     * request: on a replay the payload hash matched, so the request's decisions/actions
     * are byte-identical to the persisted children — the counts are authoritative.
     */
    private MeetingAnalysisResultIngestResponse response(
            MeetingAnalysisRun run,
            MeetingAnalysisResultIngestRequest request,
            boolean replay) {
        return MeetingAnalysisResultIngestResponse.persisted(
                run.getAnalysisRunId(),
                run.getMeetingId(),
                replay,
                request.decisions().size(),
                request.actions().size(),
                run.getSupersedesAnalysisRunId(),
                run.getGeneratedAt());
    }
}
