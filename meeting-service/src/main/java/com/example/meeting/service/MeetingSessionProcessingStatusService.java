package com.example.meeting.service;

import com.example.meeting.dto.v1.admin.MeetingSessionProcessingStatusResponse;
import com.example.meeting.dto.v1.admin.MeetingSessionProcessingStatusResponse.SavedResult;
import com.example.meeting.dto.v1.admin.MeetingSessionProcessingStatusResponse.SavedState;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingSessionErasureStatus;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionErasureRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import com.example.meeting.security.AdminTenantContext;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Exact-session readback without keeping a database lock across remote requests. */
@Service
public class MeetingSessionProcessingStatusService {
    private final MeetingCanonicalTranscriptService authorization;
    private final MeetingRepository meetings;
    private final MeetingSessionRepository sessions;
    private final MeetingSessionErasureRepository erasures;
    private final MeetingAnalysisRunRepository results;
    private final TranscriptSourceStatusClient sourceClient;
    private final MeetingIntelligenceResultAccessAuditService audit;
    private final TransactionTemplate withoutTransaction;
    private final TransactionTemplate lockedRead;
    private final Clock clock;

    @Autowired
    public MeetingSessionProcessingStatusService(MeetingCanonicalTranscriptService authorization,
            MeetingRepository meetings, MeetingSessionRepository sessions,
            MeetingSessionErasureRepository erasures, MeetingAnalysisRunRepository results,
            TranscriptSourceStatusClient sourceClient, MeetingIntelligenceResultAccessAuditService audit,
            PlatformTransactionManager transactions) {
        this(authorization, meetings, sessions, erasures, results, sourceClient, audit, transactions, Clock.systemUTC());
    }

    MeetingSessionProcessingStatusService(MeetingCanonicalTranscriptService authorization,
            MeetingRepository meetings, MeetingSessionRepository sessions,
            MeetingSessionErasureRepository erasures, MeetingAnalysisRunRepository results,
            TranscriptSourceStatusClient sourceClient, MeetingIntelligenceResultAccessAuditService audit,
            PlatformTransactionManager transactions, Clock clock) {
        this.authorization = authorization;
        this.meetings = meetings;
        this.sessions = sessions;
        this.erasures = erasures;
        this.results = results;
        this.sourceClient = sourceClient;
        this.audit = audit;
        this.clock = clock;
        withoutTransaction = new TransactionTemplate(transactions);
        withoutTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        lockedRead = new TransactionTemplate(transactions);
        lockedRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public MeetingSessionProcessingStatusResponse read(AdminTenantContext tenant, UUID meetingId, UUID sessionId) {
        return withoutTransaction.execute(ignored -> {
            authorization.requireOwnerAccess(tenant, meetingId);
            requireSession(tenant, meetingId, sessionId);
            TranscriptSourceStatusClient.Observation source;
            try {
                source = sourceClient.read(tenant.tenantId(), meetingId, sessionId);
            } catch (TranscriptSourceStatusClient.ReadFailure failure) {
                throw map(failure.failure());
            }
            if (source == null || !tenant.tenantId().equals(source.tenantId())
                    || !meetingId.equals(source.meetingId()) || !sessionId.equals(source.sessionId())) {
                throw status(HttpStatus.CONFLICT, "SOURCE_STATUS_SCOPE_MISMATCH");
            }
            return lockedRead.execute(transaction -> {
                meetings.findVisibleToOrgAndIdForUpdate(tenant.tenantId(), meetingId)
                        .orElseThrow(() -> status(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND"));
                requireSession(tenant, meetingId, sessionId);
                var run = results.findLatestBySessionVisibleToOrgForStatus(
                        meetingId, tenant.tenantId(), sessionId.toString());
                SavedResult saved = run.map(value -> saved(value, source)).orElseGet(() ->
                        new SavedResult(SavedState.NOT_FOUND, clock.instant(), null, null, null, null, null, null));
                audit.recordProcessingStatus(tenant, meetingId, sessionId);
                return new MeetingSessionProcessingStatusResponse(meetingId, sessionId, source, saved);
            });
        });
    }

    private void requireSession(AdminTenantContext tenant, UUID meetingId, UUID sessionId) {
        // Erasure can already have deleted the session; its durable tombstone takes precedence.
        erasures.findById(sessionId).ifPresent(row -> {
            if (!tenant.tenantId().equals(row.getTenantId()) || !meetingId.equals(row.getMeetingId())) {
                throw status(HttpStatus.NOT_FOUND, "MEETING_SESSION_NOT_FOUND");
            }
            if (row.getStatus() == MeetingSessionErasureStatus.COMPLETE) {
                throw status(HttpStatus.GONE, "SESSION_ERASED");
            }
            throw status(HttpStatus.LOCKED, "SESSION_ERASURE_PENDING");
        });
        sessions.findByIdAndMeetingIdVisibleToOrg(sessionId, meetingId, tenant.tenantId())
                .orElseThrow(() -> status(HttpStatus.NOT_FOUND, "MEETING_SESSION_NOT_FOUND"));
    }

    private SavedResult saved(MeetingAnalysisRun run, TranscriptSourceStatusClient.Observation source) {
        return new SavedResult(SavedState.AVAILABLE, clock.instant(), run.getAnalysisRunId(),
                run.getFinalizationVersion(), run.getFinalizedAt(), run.getRecordingOutcome(),
                run.getRecordingIncompleteReason(), matches(run, source));
    }

    private static Boolean matches(MeetingAnalysisRun run, TranscriptSourceStatusClient.Observation source) {
        var occurrence = source.finalizedOccurrence();
        if (occurrence != null && (occurrence.analysisRunId().equals(run.getAnalysisRunId())
                || Objects.equals(run.getFinalizationVersion(), occurrence.finalizationVersion()))) {
            if (!occurrence.analysisRunId().equals(run.getAnalysisRunId())
                    || !Objects.equals(run.getFinalizationVersion(), occurrence.finalizationVersion())
                    || !occurrence.finalizedAt().equals(run.getFinalizedAt())
                    || occurrence.recordingOutcome() != run.getRecordingOutcome()
                    || !Objects.equals(occurrence.recordingIncompleteReason(), run.getRecordingIncompleteReason())) {
                throw status(HttpStatus.CONFLICT, "SOURCE_RESULT_OCCURRENCE_MISMATCH");
            }
            return true;
        }
        if (source.cycleVersion() != null && run.getFinalizationVersion() != null
                && run.getFinalizationVersion() < source.cycleVersion()) {
            return false;
        }
        // A result may arrive after the upstream observation, including during quiescence.
        return null;
    }

    private static ResponseStatusException map(TranscriptSourceStatusClient.Failure failure) {
        return switch (failure) {
            case ERASED -> status(HttpStatus.GONE, "SESSION_ERASED");
            case ERASURE_PENDING -> status(HttpStatus.LOCKED, "SESSION_ERASURE_PENDING");
            case INVALID_RESPONSE, INTEGRITY_CONFLICT -> status(HttpStatus.CONFLICT, "SOURCE_STATUS_INVALID");
            case UNAVAILABLE -> status(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_STATUS_UNAVAILABLE");
        };
    }

    private static ResponseStatusException status(HttpStatus code, String reason) {
        return new ResponseStatusException(code, reason);
    }
}
