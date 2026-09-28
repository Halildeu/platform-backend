package com.example.transcript.service;

import com.example.common.meeting.events.RecordingOutcome;
import com.example.transcript.dto.TranscriptSourceStatusDto;
import com.example.transcript.dto.TranscriptSourceStatusDto.FailureCode;
import com.example.transcript.dto.TranscriptSourceStatusDto.FinalizedOccurrence;
import com.example.transcript.dto.TranscriptSourceStatusDto.State;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionErasureStatus;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.repository.TranscriptSessionErasureTombstoneRepository;
import com.example.transcript.security.AdminTenantContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Reads persisted source observations without starting, retrying or completing work. */
@Service
public class TranscriptSourceStatusService {

    private final TranscriptSessionAssociationRepository associations;
    private final TranscriptFinalizationRepository finalizations;
    private final TranscriptSessionErasureTombstoneRepository erasures;
    private final SessionErasureFence fence;
    private final TranscriptAccessAuditService audit;
    private final Clock clock;

    public TranscriptSourceStatusService(
            TranscriptSessionAssociationRepository associations,
            TranscriptFinalizationRepository finalizations,
            TranscriptSessionErasureTombstoneRepository erasures,
            SessionErasureFence fence,
            TranscriptAccessAuditService audit,
            @Qualifier("transcriptFinalizationClock") Clock clock) {
        this.associations = associations;
        this.finalizations = finalizations;
        this.erasures = erasures;
        this.fence = fence;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public TranscriptSourceStatusDto read(
            UUID tenantId, UUID meetingId, UUID sessionId,
            UUID requestedTenantId, String serviceSubject) {
        if (!tenantId.equals(requestedTenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_SCOPE_MISMATCH");
        }
        if (serviceSubject == null || serviceSubject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "SERVICE_SUBJECT_REQUIRED");
        }
        fence.lock(SessionErasureFence.canonicalKey(
                new SessionErasureFence.UUIDScope(tenantId, meetingId, sessionId)));
        erasures.findByTenantIdAndMeetingIdAndSessionId(tenantId, meetingId, sessionId)
                .ifPresent(erasure -> {
                    // Unlike an exact legal-hold snapshot read, this status observes
                    // mutable session state. Do not disclose it during any erasure.
                    if (erasure.getStatus() == TranscriptSessionErasureStatus.COMPLETE) {
                        throw new ResponseStatusException(HttpStatus.GONE, "TRANSCRIPT_ERASED");
                    }
                    throw new ResponseStatusException(HttpStatus.LOCKED, "TRANSCRIPT_ERASURE_PENDING");
                });

        TranscriptSessionAssociation association = associations
                .findCanonicalForUpdate(tenantId, meetingId, sessionId).orElse(null);
        State state = State.UNKNOWN;
        FailureCode failure = null;
        FinalizedOccurrence occurrence = null;
        RecordingOutcome outcome = RecordingOutcome.UNKNOWN;
        String incompleteReason = null;
        if (association != null) {
            // The existing validated recording.finished consumer owns this marker.
            // finishObservedAt alone is not closure evidence; editorial snapshots
            // can be FINALIZED before the marker exists.
            outcome = association.getRecordingFinishedAt() == null
                    ? RecordingOutcome.UNKNOWN : RecordingOutcome.FINISHED;
            if (association.getFinalizationState() == null) {
                throw conflict();
            }
            outcome.validateReason(incompleteReason);
            // Editorial snapshots may precede closure and remain FINALIZED even
            // as capture adds text. They are not proof of a completed source cycle.
            state = outcome == RecordingOutcome.UNKNOWN ? State.AWAITING_CLOSURE : switch (association.getFinalizationState()) {
                case AWAITING_FINISH -> State.AWAITING_CLOSURE;
                case QUIESCING -> State.QUIESCING;
                case TIMED_OUT -> State.FAILED;
                case FINALIZED -> State.FINALIZED;
            };
            if (state == State.FAILED) {
                failure = allowlistedFailure(association.getFinalizationErrorCode());
            }
            if (state == State.FINALIZED) {
                if (association.getFinalizationVersion() < 1
                        || association.getFinalizationVersion() != association.getFinalizationCycleVersion()) {
                    throw conflict();
                }
                // Retention does not acquire the canonical fence. A row lock also
                // orders this observation against deletion of the matching snapshot.
                TranscriptFinalization snapshot = finalizations.findVisibleOccurrenceForStatus(
                        tenantId, meetingId, sessionId, association.getFinalizationVersion()).orElse(null);
                if (snapshot == null) {
                    // Absence alone proves neither retention expiry nor AI failure.
                    state = State.UNKNOWN;
                } else {
                    if (snapshot.getAnalysisRunId() == null || snapshot.getFinalizedAt() == null) {
                        throw conflict();
                    }
                    // The existing immutable snapshot has no closure provenance.
                    // Never copy the mutable association's later finish onto it.
                    occurrence = new FinalizedOccurrence(snapshot.getFinalizationVersion(),
                            snapshot.getAnalysisRunId(), snapshot.getFinalizedAt(),
                            RecordingOutcome.UNKNOWN, null);
                }
            }
        }
        TranscriptSourceStatusDto response = new TranscriptSourceStatusDto(
                tenantId, meetingId, sessionId, state,
                association == null ? null : association.getFinalizationCycleVersion(),
                association == null ? null : association.getVersion(), clock.instant(), failure,
                outcome, incompleteReason, occurrence);
        audit.recordStatus(new AdminTenantContext(tenantId, serviceSubject, serviceSubject),
                meetingId, sessionId);
        return response;
    }

    private static FailureCode allowlistedFailure(String code) {
        if (code == null) {
            return null;
        }
        try {
            return FailureCode.valueOf(code);
        } catch (IllegalArgumentException unknownCode) {
            // Never expose arbitrary stored exception/error text.
            return null;
        }
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "SOURCE_STATUS_INTEGRITY_MISMATCH");
    }
}
