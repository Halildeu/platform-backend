package com.example.meeting.service;

import com.example.meeting.config.MeetingSessionErasureProperties;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingAnalysisRunDestructionReason;
import com.example.meeting.model.MeetingSession;
import com.example.meeting.model.MeetingSessionErasure;
import com.example.meeting.model.MeetingSessionErasureAudit;
import com.example.meeting.model.MeetingSessionErasureStatus;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionErasureAuditRepository;
import com.example.meeting.repository.MeetingSessionErasureRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import com.example.meeting.security.AdminTenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Transactional state transitions for durable cross-service session erasure. */
@Service
public class MeetingSessionErasureService {

    private final MeetingRepository meetings;
    private final MeetingSessionRepository sessions;
    private final MeetingAnalysisRunRepository analysisRuns;
    private final MeetingSessionErasureRepository erasures;
    private final MeetingSessionErasureAuditRepository audits;
    private final MeetingAnalysisRunDestructionRecorder destructionRecorder;
    private final MeetingSessionErasureProperties properties;
    private final Clock clock;

    @Autowired
    public MeetingSessionErasureService(
            MeetingRepository meetings,
            MeetingSessionRepository sessions,
            MeetingAnalysisRunRepository analysisRuns,
            MeetingSessionErasureRepository erasures,
            MeetingSessionErasureAuditRepository audits,
            MeetingAnalysisRunDestructionRecorder destructionRecorder,
            MeetingSessionErasureProperties properties) {
        this(meetings, sessions, analysisRuns, erasures, audits,
                destructionRecorder, properties, Clock.systemUTC());
    }

    MeetingSessionErasureService(
            MeetingRepository meetings,
            MeetingSessionRepository sessions,
            MeetingAnalysisRunRepository analysisRuns,
            MeetingSessionErasureRepository erasures,
            MeetingSessionErasureAuditRepository audits,
            MeetingAnalysisRunDestructionRecorder destructionRecorder,
            MeetingSessionErasureProperties properties,
            Clock clock) {
        this.meetings = meetings;
        this.sessions = sessions;
        this.analysisRuns = analysisRuns;
        this.erasures = erasures;
        this.audits = audits;
        this.destructionRecorder = destructionRecorder;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public MeetingSessionErasureStatus request(
            AdminTenantContext tenant, UUID meetingId, UUID sessionId) {
        meetings.findVisibleToOrgAndIdForUpdate(tenant.tenantId(), meetingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Meeting not found."));
        Optional<MeetingSessionErasure> existing = erasures.findBySessionIdForUpdate(sessionId);
        if (existing.isPresent()) {
            MeetingSessionErasure row = existing.get();
            if (!tenant.tenantId().equals(row.getTenantId()) || !meetingId.equals(row.getMeetingId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Meeting session not found.");
            }
            return row.getStatus();
        }

        MeetingSession session = sessions.findByIdAndMeetingIdVisibleToOrg(
                        sessionId, meetingId, tenant.tenantId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Meeting session not found."));
        List<MeetingAnalysisRun> scope = analysisRuns.findErasureScopeForUpdate(
                meetingId, tenant.tenantId(), sessionId.toString());
        boolean held = scope.stream().anyMatch(MeetingAnalysisRun::isLegalHold);
        Instant now = clock.instant();
        MeetingSessionErasure row = new MeetingSessionErasure();
        row.setSessionId(sessionId);
        row.setTenantId(session.getTenantId());
        row.setOrgId(session.getEffectiveOrgId());
        row.setMeetingId(meetingId);
        row.setSourceSessionId(normalizeAlias(session.getExternalSessionId()));
        row.setSourceSessionHash(SessionAliasHasher.sha256(session.getExternalSessionId()));
        row.setStatus(held ? MeetingSessionErasureStatus.HELD : MeetingSessionErasureStatus.PENDING);
        row.setNextAttemptAt(held ? now.plus(properties.getHeldRetryDelay()) : now);
        row.setRequestedAt(now);
        row.setUpdatedAt(now);
        erasures.saveAndFlush(row);
        audit(row, row.getStatus(), 0, 0, now);
        return row.getStatus();
    }

    public void assertSourceNotErased(UUID tenantId, UUID meetingId, String sourceSessionId) {
        String hash = SessionAliasHasher.sha256(sourceSessionId);
        if (hash != null && erasures.existsByTenantIdAndMeetingIdAndSourceSessionHash(
                tenantId, meetingId, hash)) {
            throw erased();
        }
    }

    public void assertSessionNotErased(UUID tenantId, UUID meetingId, UUID sessionId) {
        if (erasures.existsByTenantIdAndMeetingIdAndSessionId(tenantId, meetingId, sessionId)) {
            throw erased();
        }
    }

    @Transactional
    public int recoverStaleLeases() {
        Instant now = clock.instant();
        return erasures.recoverStaleLeases(now, now.plus(properties.getRetryDelay()));
    }

    @Transactional
    public List<MeetingSessionErasure> claim(UUID claimToken, String owner) {
        Instant now = clock.instant();
        erasures.claimBatch(
                now, now.plus(properties.getLeaseDuration()), owner,
                claimToken, properties.getBatchSize());
        return erasures.findByClaimToken(claimToken);
    }

    @Transactional
    public LocalResult eraseLocal(MeetingSessionErasure claimed, UUID claimToken) {
        UUID orgId = claimed.getOrgId() == null ? claimed.getTenantId() : claimed.getOrgId();
        // Parent lock is the common ordering fence used by analysis ingestion.
        meetings.findVisibleToOrgAndIdForUpdate(orgId, claimed.getMeetingId());
        MeetingSessionErasure row = erasures.findClaimedForUpdate(
                        claimed.getSessionId(), claimToken)
                .orElse(null);
        if (row == null || row.getStatus() != MeetingSessionErasureStatus.ACTIVE) {
            return LocalResult.leaseLost();
        }

        List<MeetingAnalysisRun> scope = analysisRuns.findErasureScopeForUpdate(
                row.getMeetingId(), row.getTenantId(), row.getSessionId().toString());
        if (scope.stream().anyMatch(MeetingAnalysisRun::isLegalHold)) {
            moveToHeld(row, clock.instant(), 0, 0);
            return LocalResult.held();
        }

        int deleted = 0;
        if (!row.isLocalErased()) {
            deleted = analysisRuns.deleteErasureScope(
                    row.getMeetingId(), row.getTenantId(), row.getSessionId().toString());
            destructionRecorder.recordDestroyed(
                    scope, MeetingAnalysisRunDestructionReason.ERASURE, clock.instant());
            // The delete predicate is the final legal-hold fence. Never cascade the
            // session while a row that became held still survives.
            if (analysisRuns.existsLegalHoldForErasure(
                    row.getMeetingId(), row.getTenantId(), row.getSessionId().toString())) {
                moveToHeld(row, clock.instant(), deleted, 0);
                return LocalResult.held();
            }
            sessions.findByIdAndMeetingIdVisibleToOrg(
                    row.getSessionId(), row.getMeetingId(), row.getTenantId())
                    .ifPresent(sessions::delete);
            sessions.flush();
            row.setLocalErased(true);
            row.setUpdatedAt(clock.instant());
            erasures.saveAndFlush(row);
            audit(row, MeetingSessionErasureStatus.ACTIVE, deleted, 0, row.getUpdatedAt());
        }
        return LocalResult.ready(
                row.getTenantId(), row.getMeetingId(), row.getSessionId(), row.getSourceSessionId());
    }

    @Transactional
    public void markRemoteHeld(UUID sessionId, UUID claimToken) {
        MeetingSessionErasure row = erasures.findClaimedForUpdate(sessionId, claimToken).orElse(null);
        if (row != null) {
            moveToHeld(row, clock.instant(), 0, 0);
        }
    }

    @Transactional
    public void markComplete(UUID sessionId, UUID claimToken, int remoteDeletedCount) {
        MeetingSessionErasure row = erasures.findClaimedForUpdate(sessionId, claimToken).orElse(null);
        if (row == null || !row.isLocalErased()) {
            return;
        }
        Instant now = clock.instant();
        row.setRemoteErased(true);
        row.setStatus(MeetingSessionErasureStatus.COMPLETE);
        row.setSourceSessionId(null);
        row.setCompletedAt(now);
        row.setNextAttemptAt(now);
        row.setLastErrorCode(null);
        clearClaim(row);
        row.setUpdatedAt(now);
        erasures.saveAndFlush(row);
        audit(row, MeetingSessionErasureStatus.COMPLETE, 0, remoteDeletedCount, now);
    }

    @Transactional
    public void markFailure(UUID sessionId, UUID claimToken, String errorCode) {
        MeetingSessionErasure row = erasures.findClaimedForUpdate(sessionId, claimToken).orElse(null);
        if (row == null) {
            return;
        }
        Instant now = clock.instant();
        row.setStatus(MeetingSessionErasureStatus.PENDING);
        row.setNextAttemptAt(now.plus(properties.getRetryDelay()));
        row.setLastErrorCode(boundedCode(errorCode));
        clearClaim(row);
        row.setUpdatedAt(now);
        erasures.saveAndFlush(row);
        audit(row, MeetingSessionErasureStatus.PENDING, 0, 0, now);
    }

    private void moveToHeld(
            MeetingSessionErasure row, Instant now, int localDeletedCount, int remoteDeletedCount) {
        row.setStatus(MeetingSessionErasureStatus.HELD);
        row.setNextAttemptAt(now.plus(properties.getHeldRetryDelay()));
        row.setLastErrorCode("LEGAL_HOLD");
        clearClaim(row);
        row.setUpdatedAt(now);
        erasures.saveAndFlush(row);
        audit(row, MeetingSessionErasureStatus.HELD,
                localDeletedCount, remoteDeletedCount, now);
    }

    private void audit(
            MeetingSessionErasure row,
            MeetingSessionErasureStatus state,
            int localDeletedCount,
            int remoteDeletedCount,
            Instant now) {
        MeetingSessionErasureAudit audit = new MeetingSessionErasureAudit();
        audit.setId(UUID.randomUUID());
        audit.setSessionId(row.getSessionId());
        audit.setState(state);
        audit.setLocalDeletedCount(Math.max(0, localDeletedCount));
        audit.setRemoteDeletedCount(Math.max(0, remoteDeletedCount));
        audit.setExecutedAt(now);
        audits.save(audit);
    }

    private static void clearClaim(MeetingSessionErasure row) {
        row.setClaimToken(null);
        row.setProcessingOwner(null);
        row.setClaimedAt(null);
        row.setLeaseExpiresAt(null);
    }

    private static String normalizeAlias(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String boundedCode(String value) {
        if (value == null || value.isBlank()) {
            return "REMOTE_FAILURE";
        }
        String normalized = value.replaceAll("[^A-Z0-9_]", "_");
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    private static ResponseStatusException erased() {
        return new ResponseStatusException(HttpStatus.GONE, "MEETING_SESSION_ERASED");
    }

    public record LocalResult(
            Status status,
            UUID tenantId,
            UUID meetingId,
            UUID sessionId,
            String sourceSessionId) {
        static LocalResult leaseLost() { return new LocalResult(Status.LEASE_LOST, null, null, null, null); }
        static LocalResult held() { return new LocalResult(Status.HELD, null, null, null, null); }
        static LocalResult ready(UUID tenantId, UUID meetingId, UUID sessionId, String sourceSessionId) {
            return new LocalResult(Status.READY, tenantId, meetingId, sessionId, sourceSessionId);
        }
        public enum Status { READY, HELD, LEASE_LOST }
    }
}
