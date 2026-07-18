package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingAnalysisRunDestructionReason;
import com.example.meeting.model.MeetingAnalysisRunDestructionTombstone;
import com.example.meeting.model.MeetingSessionErasure;
import com.example.meeting.model.MeetingSessionErasureStatus;
import com.example.meeting.repository.MeetingAnalysisRunDestructionTombstoneRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionErasureRepository;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MeetingCanonicalTranscriptServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEETING = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SESSION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant FINALIZED_AT = Instant.parse("2026-07-18T12:00:00Z");
    private static final String HASH = "a".repeat(64);
    private static final AdminTenantContext TENANT_CONTEXT =
            new AdminTenantContext(TENANT, "stable-sub", "legacy-user");

    @Mock private MeetingRepository meetings;
    @Mock private MeetingAnalysisRunRepository analysisRuns;
    @Mock private MeetingAnalysisRunDestructionTombstoneRepository destructionTombstones;
    @Mock private MeetingSessionErasureRepository sessionErasures;
    @Mock private ObjectProvider<OpenFgaAuthzService> authzProvider;
    @Mock private OpenFgaAuthzService authz;
    @Mock private CanonicalTranscriptClient transcriptClient;
    @Mock private MeetingIntelligenceResultAccessAuditService auditService;

    private MeetingCanonicalTranscriptService service;
    private Meeting meeting;
    private MeetingAnalysisRun run;

    @BeforeEach
    void setUp() {
        service = new MeetingCanonicalTranscriptService(
                meetings, analysisRuns, destructionTombstones, sessionErasures,
                authzProvider, transcriptClient, auditService, false);
        meeting = meeting();
        run = run();
        when(meetings.findVisibleToOrgAndId(TENANT, MEETING)).thenReturn(Optional.of(meeting));
        when(authzProvider.getIfAvailable()).thenReturn(authz);
        when(authz.isEnabled()).thenReturn(true);
        when(authz.checkPrincipal(
                "user:stable-sub", MeetingAuthz.BLOCKED,
                MeetingAuthz.OBJECT_TYPE, MEETING.toString())).thenReturn(false);
        when(authz.checkPrincipal(
                "user:stable-sub", MeetingAuthz.OWNER,
                MeetingAuthz.OBJECT_TYPE, MEETING.toString())).thenReturn(true);
    }

    @Test
    void ownerReadsOnlyExactPersistedRunTupleAndWritesMetadataAudit() {
        when(analysisRuns.findVisibleExactRun(RUN, MEETING, TENANT)).thenReturn(Optional.of(run));
        when(transcriptClient.read(TENANT, MEETING, SESSION, 7L)).thenReturn(snapshot(HASH));

        var response = service.read(TENANT_CONTEXT, MEETING, RUN);

        assertThat(response.analysisRunId()).isEqualTo(RUN);
        assertThat(response.meetingId()).isEqualTo(MEETING);
        assertThat(response.sessionId()).isEqualTo(SESSION);
        assertThat(response.finalizationVersion()).isEqualTo(7L);
        assertThat(response.finalizedAt()).isEqualTo(FINALIZED_AT);
        assertThat(response.transcriptSha256()).isEqualTo(HASH);
        assertThat(response.transcript()).isEqualTo("canonical text");
        assertThat(response.segments()).extracting("text").containsExactly("canonical text");
        verify(auditService).recordCanonicalTranscriptRead(TENANT_CONTEXT, MEETING, RUN);
    }

    @Test
    void nonOwnerIsForbiddenBeforeRunOrTranscriptDisclosure() {
        when(authz.checkPrincipal(
                "user:stable-sub", MeetingAuthz.OWNER,
                MeetingAuthz.OBJECT_TYPE, MEETING.toString())).thenReturn(false);

        assertStatus(() -> service.read(TENANT_CONTEXT, MEETING, RUN),
                403, "TRANSCRIPT_FORBIDDEN");

        verify(analysisRuns, never()).findVisibleExactRun(any(), any(), any());
        verify(transcriptClient, never()).read(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void exactDestroyedRunDistinguishesErasureFromRetention() {
        when(analysisRuns.findVisibleExactRun(RUN, MEETING, TENANT)).thenReturn(Optional.empty());
        MeetingAnalysisRunDestructionTombstone tombstone = destruction(
                MeetingAnalysisRunDestructionReason.ERASURE);
        when(destructionTombstones.findByAnalysisRunIdAndTenantIdAndMeetingId(
                RUN, TENANT, MEETING)).thenReturn(Optional.of(tombstone));
        assertStatus(() -> service.read(TENANT_CONTEXT, MEETING, RUN),
                410, "TRANSCRIPT_ERASED");

        tombstone.setReason(MeetingAnalysisRunDestructionReason.RETENTION);
        assertStatus(() -> service.read(TENANT_CONTEXT, MEETING, RUN),
                410, "TRANSCRIPT_RETENTION_EXPIRED");
    }

    @Test
    void erasureIntentWithholdsContentUnlessTheExactRunIsLegallyHeld() {
        when(analysisRuns.findVisibleExactRun(RUN, MEETING, TENANT)).thenReturn(Optional.of(run));
        MeetingSessionErasure erasure = new MeetingSessionErasure();
        erasure.setSessionId(SESSION);
        erasure.setTenantId(TENANT);
        erasure.setMeetingId(MEETING);
        erasure.setStatus(MeetingSessionErasureStatus.PENDING);
        when(sessionErasures.findById(SESSION)).thenReturn(Optional.of(erasure));

        assertStatus(() -> service.read(TENANT_CONTEXT, MEETING, RUN),
                423, "TRANSCRIPT_ERASURE_PENDING");
        verify(transcriptClient, never()).read(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong());

        run.setLegalHold(true);
        erasure.setStatus(MeetingSessionErasureStatus.HELD);
        when(transcriptClient.read(TENANT, MEETING, SESSION, 7L)).thenReturn(snapshot(HASH));
        assertThat(service.read(TENANT_CONTEXT, MEETING, RUN).state()).isEqualTo("LEGAL_HOLD");
    }

    @Test
    void mismatchedRemoteHashFailsWithoutAuditOrContentResponse() {
        when(analysisRuns.findVisibleExactRun(RUN, MEETING, TENANT)).thenReturn(Optional.of(run));
        when(transcriptClient.read(TENANT, MEETING, SESSION, 7L))
                .thenReturn(snapshot("b".repeat(64)));

        assertStatus(() -> service.read(TENANT_CONTEXT, MEETING, RUN),
                409, "TRANSCRIPT_RESULT_SCOPE_MISMATCH");
        verify(auditService, never()).recordCanonicalTranscriptRead(any(), any(), any());
    }

    private static void assertStatus(Runnable action, int status, String reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(status);
                    assertThat(ex.getReason()).isEqualTo(reason);
                });
    }

    private static Meeting meeting() {
        Meeting meeting = new Meeting();
        ReflectionTestUtils.setField(meeting, "id", MEETING);
        meeting.setTenantId(TENANT);
        meeting.setOrgId(TENANT);
        meeting.setCreatedBySubject("stable-sub");
        return meeting;
    }

    private static MeetingAnalysisRun run() {
        MeetingAnalysisRun run = new MeetingAnalysisRun();
        run.setAnalysisRunId(RUN);
        run.setTenantId(TENANT);
        run.setOrgId(TENANT);
        run.setMeetingId(MEETING);
        run.setTranscriptSessionId(SESSION.toString());
        run.setFinalizationVersion(7L);
        run.setFinalizedAt(FINALIZED_AT);
        run.setTranscriptSha256(HASH);
        return run;
    }

    private static CanonicalTranscriptClient.Snapshot snapshot(String hash) {
        return new CanonicalTranscriptClient.Snapshot(
                TENANT, MEETING, SESSION, 7L, FINALIZED_AT, "FINALIZED",
                "canonical text", hash, 1,
                List.of(new CanonicalTranscriptClient.Segment("canonical text", 0.0, 1.0)));
    }

    private static MeetingAnalysisRunDestructionTombstone destruction(
            MeetingAnalysisRunDestructionReason reason) {
        MeetingAnalysisRunDestructionTombstone tombstone =
                new MeetingAnalysisRunDestructionTombstone();
        tombstone.setAnalysisRunId(RUN);
        tombstone.setTenantId(TENANT);
        tombstone.setOrgId(TENANT);
        tombstone.setMeetingId(MEETING);
        tombstone.setSessionId(SESSION);
        tombstone.setReason(reason);
        tombstone.setDestroyedAt(FINALIZED_AT.plusSeconds(1));
        return tombstone;
    }
}
