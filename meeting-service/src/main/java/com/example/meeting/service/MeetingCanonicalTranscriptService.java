package com.example.meeting.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.CanonicalMeetingTranscriptResponse;
import com.example.meeting.dto.v1.admin.CanonicalMeetingTranscriptSegment;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingAnalysisRunDestructionReason;
import com.example.meeting.model.MeetingSessionErasure;
import com.example.meeting.model.MeetingSessionErasureStatus;
import com.example.meeting.repository.MeetingAnalysisRunDestructionTombstoneRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionErasureRepository;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Owner-authorized exact analysis-run to canonical transcript readback. */
@Service
public class MeetingCanonicalTranscriptService {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> STATES = Set.of("FINALIZED", "LEGAL_HOLD");

    private final MeetingRepository meetings;
    private final MeetingAnalysisRunRepository analysisRuns;
    private final MeetingAnalysisRunDestructionTombstoneRepository destructionTombstones;
    private final MeetingSessionErasureRepository sessionErasures;
    private final ObjectProvider<OpenFgaAuthzService> authzProvider;
    private final CanonicalTranscriptClient transcriptClient;
    private final MeetingIntelligenceResultAccessAuditService auditService;
    private final boolean legacyUserIdFallbackEnabled;

    public MeetingCanonicalTranscriptService(
            MeetingRepository meetings,
            MeetingAnalysisRunRepository analysisRuns,
            MeetingAnalysisRunDestructionTombstoneRepository destructionTombstones,
            MeetingSessionErasureRepository sessionErasures,
            ObjectProvider<OpenFgaAuthzService> authzProvider,
            CanonicalTranscriptClient transcriptClient,
            MeetingIntelligenceResultAccessAuditService auditService,
            @Value("${meeting.authz.object-principal.legacy-user-id-fallback-enabled:false}")
                    boolean legacyUserIdFallbackEnabled) {
        this.meetings = meetings;
        this.analysisRuns = analysisRuns;
        this.destructionTombstones = destructionTombstones;
        this.sessionErasures = sessionErasures;
        this.authzProvider = authzProvider;
        this.transcriptClient = transcriptClient;
        this.auditService = auditService;
        this.legacyUserIdFallbackEnabled = legacyUserIdFallbackEnabled;
    }

    public CanonicalMeetingTranscriptResponse read(
            AdminTenantContext tenant, UUID meetingId, UUID analysisRunId) {
        Meeting meeting = meetings.findVisibleToOrgAndId(tenant.tenantId(), meetingId)
                .orElseThrow(() -> status(HttpStatus.NOT_FOUND, "MEETING_NOT_FOUND"));
        requireOwner(tenant, meeting);

        MeetingAnalysisRun run = analysisRuns.findVisibleExactRun(
                        analysisRunId, meetingId, tenant.tenantId())
                .orElseThrow(() -> destroyedStatus(tenant, meetingId, analysisRunId));
        UUID sessionId = canonicalSessionId(run);
        requireReadableErasureState(tenant.tenantId(), meetingId, sessionId, run);
        if (run.getFinalizationVersion() == null || run.getFinalizationVersion() < 1
                || run.getFinalizedAt() == null || !validHash(run.getTranscriptSha256())) {
            throw status(HttpStatus.CONFLICT, "TRANSCRIPT_OCCURRENCE_TUPLE_UNAVAILABLE");
        }

        CanonicalTranscriptClient.Snapshot snapshot;
        try {
            snapshot = transcriptClient.read(
                    tenant.tenantId(), meetingId, sessionId, run.getFinalizationVersion());
        } catch (CanonicalTranscriptClient.ReadFailure failure) {
            throw map(failure.failure());
        }
        validateExactTuple(tenant, run, sessionId, snapshot);

        List<CanonicalMeetingTranscriptSegment> segments = snapshot.segments().stream()
                .map(segment -> new CanonicalMeetingTranscriptSegment(
                        segment.text(), segment.start(), segment.end()))
                .toList();
        String state = run.isLegalHold() || "LEGAL_HOLD".equals(snapshot.state())
                ? "LEGAL_HOLD" : "FINALIZED";
        CanonicalMeetingTranscriptResponse response = new CanonicalMeetingTranscriptResponse(
                analysisRunId,
                meetingId,
                sessionId,
                run.getFinalizationVersion(),
                run.getFinalizedAt(),
                state,
                snapshot.transcript(),
                snapshot.transcriptSha256(),
                snapshot.segmentCount(),
                segments);
        auditService.recordCanonicalTranscriptRead(tenant, meetingId, analysisRunId);
        return response;
    }

    private void requireOwner(AdminTenantContext tenant, Meeting meeting) {
        OpenFgaAuthzService authz = authzProvider.getIfAvailable();
        if (authz == null || !authz.isEnabled()) {
            throw status(HttpStatus.SERVICE_UNAVAILABLE, "TRANSCRIPT_AUTHORIZATION_UNAVAILABLE");
        }
        String stable = principalRef(tenant.subject());
        String objectId = meeting.getId().toString();
        try {
            if (authz.checkPrincipal(
                    stable, MeetingAuthz.BLOCKED, MeetingAuthz.OBJECT_TYPE, objectId)) {
                throw status(HttpStatus.FORBIDDEN, "TRANSCRIPT_FORBIDDEN");
            }
            if (authz.checkPrincipal(
                    stable, MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, objectId)) {
                return;
            }
            if (legacyUserIdFallbackEnabled
                    && tenant.subject().equals(meeting.getCreatedBySubject())) {
                String legacy = principalRef(tenant.authzPrincipal());
                if (!legacy.equals(stable)
                        && !authz.checkPrincipal(
                                legacy, MeetingAuthz.BLOCKED, MeetingAuthz.OBJECT_TYPE, objectId)
                        && authz.checkPrincipal(
                                legacy, MeetingAuthz.OWNER, MeetingAuthz.OBJECT_TYPE, objectId)) {
                    return;
                }
            }
        } catch (ResponseStatusException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "TRANSCRIPT_AUTHORIZATION_UNAVAILABLE",
                    unavailable);
        }
        throw status(HttpStatus.FORBIDDEN, "TRANSCRIPT_FORBIDDEN");
    }

    private ResponseStatusException destroyedStatus(
            AdminTenantContext tenant, UUID meetingId, UUID analysisRunId) {
        return destructionTombstones.findByAnalysisRunIdAndTenantIdAndMeetingId(
                        analysisRunId, tenant.tenantId(), meetingId)
                .map(tombstone -> tombstone.getReason()
                        == MeetingAnalysisRunDestructionReason.ERASURE
                                ? status(HttpStatus.GONE, "TRANSCRIPT_ERASED")
                                : status(HttpStatus.GONE, "TRANSCRIPT_RETENTION_EXPIRED"))
                .orElseGet(() -> status(HttpStatus.NOT_FOUND, "ANALYSIS_RESULT_NOT_FOUND"));
    }

    private void requireReadableErasureState(
            UUID tenantId, UUID meetingId, UUID sessionId, MeetingAnalysisRun run) {
        sessionErasures.findById(sessionId).ifPresent(erasure -> {
            if (!tenantId.equals(erasure.getTenantId()) || !meetingId.equals(erasure.getMeetingId())) {
                throw status(HttpStatus.CONFLICT, "TRANSCRIPT_ERASURE_SCOPE_MISMATCH");
            }
            if (erasure.getStatus() == MeetingSessionErasureStatus.COMPLETE) {
                throw status(HttpStatus.GONE, "TRANSCRIPT_ERASED");
            }
            if (erasure.getStatus() != MeetingSessionErasureStatus.HELD || !run.isLegalHold()) {
                throw status(HttpStatus.LOCKED, "TRANSCRIPT_ERASURE_PENDING");
            }
        });
    }

    private static UUID canonicalSessionId(MeetingAnalysisRun run) {
        try {
            return UUID.fromString(run.getTranscriptSessionId());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw status(HttpStatus.CONFLICT, "TRANSCRIPT_OCCURRENCE_TUPLE_UNAVAILABLE");
        }
    }

    private static void validateExactTuple(
            AdminTenantContext tenant,
            MeetingAnalysisRun run,
            UUID sessionId,
            CanonicalTranscriptClient.Snapshot snapshot) {
        boolean exact = snapshot != null
                && tenant.tenantId().equals(snapshot.tenantId())
                && run.getMeetingId().equals(snapshot.meetingId())
                && sessionId.equals(snapshot.sessionId())
                && run.getFinalizationVersion() == snapshot.finalizationVersion()
                && run.getFinalizedAt().equals(snapshot.finalizedAt())
                && STATES.contains(snapshot.state())
                && snapshot.transcript() != null
                && snapshot.segments() != null
                && snapshot.segmentCount() == snapshot.segments().size()
                && validHash(snapshot.transcriptSha256())
                && constantTimeHashEquals(run.getTranscriptSha256(), snapshot.transcriptSha256());
        if (!exact) {
            throw status(HttpStatus.CONFLICT, "TRANSCRIPT_RESULT_SCOPE_MISMATCH");
        }
    }

    private static boolean validHash(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private static boolean constantTimeHashEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static String principalRef(String value) {
        if (value == null || value.isBlank()) {
            throw status(HttpStatus.FORBIDDEN, "TRANSCRIPT_FORBIDDEN");
        }
        return value.startsWith("user:") ? value : "user:" + value;
    }

    private static ResponseStatusException map(CanonicalTranscriptClient.Failure failure) {
        return switch (failure) {
            case ERASED -> status(HttpStatus.GONE, "TRANSCRIPT_ERASED");
            case RETENTION_EXPIRED -> status(HttpStatus.GONE, "TRANSCRIPT_RETENTION_EXPIRED");
            case ERASURE_PENDING -> status(HttpStatus.LOCKED, "TRANSCRIPT_ERASURE_PENDING");
            case INTEGRITY_CONFLICT, INVALID_RESPONSE ->
                    status(HttpStatus.CONFLICT, "TRANSCRIPT_INTEGRITY_MISMATCH");
            case UNAVAILABLE -> status(HttpStatus.SERVICE_UNAVAILABLE, "TRANSCRIPT_READ_UNAVAILABLE");
        };
    }

    private static ResponseStatusException status(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}
