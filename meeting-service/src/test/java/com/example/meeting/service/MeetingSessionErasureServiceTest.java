package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.meeting.config.MeetingSessionErasureProperties;
import com.example.meeting.model.Meeting;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MeetingSessionErasureServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID CLAIM = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Mock private MeetingRepository meetings;
    @Mock private MeetingSessionRepository sessions;
    @Mock private MeetingAnalysisRunRepository analysisRuns;
    @Mock private MeetingSessionErasureRepository erasures;
    @Mock private MeetingSessionErasureAuditRepository audits;
    @Mock private MeetingAnalysisRunDestructionRecorder destructionRecorder;

    private MeetingSessionErasureService service;

    @BeforeEach
    void setUp() {
        service = new MeetingSessionErasureService(
                meetings, sessions, analysisRuns, erasures, audits, destructionRecorder,
                new MeetingSessionErasureProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void requestPersistsCanonicalAndRecordedAliasAsDurablePendingTombstone() {
        Meeting meeting = new Meeting();
        MeetingSession session = session();
        when(meetings.findVisibleToOrgAndIdForUpdate(TENANT, MEETING))
                .thenReturn(Optional.of(meeting));
        when(erasures.findBySessionIdForUpdate(SESSION)).thenReturn(Optional.empty());
        when(sessions.findByIdAndMeetingIdVisibleToOrg(SESSION, MEETING, TENANT))
                .thenReturn(Optional.of(session));
        when(analysisRuns.findErasureScopeForUpdate(MEETING, TENANT, SESSION.toString()))
                .thenReturn(List.of());
        when(erasures.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.request(
                new AdminTenantContext(TENANT, "subject", "subject"), MEETING, SESSION))
                .isEqualTo(MeetingSessionErasureStatus.PENDING);

        ArgumentCaptor<MeetingSessionErasure> row = ArgumentCaptor.forClass(MeetingSessionErasure.class);
        verify(erasures).saveAndFlush(row.capture());
        assertThat(row.getValue().getSessionId()).isEqualTo(SESSION);
        assertThat(row.getValue().getSourceSessionId()).isEqualTo("REC-42");
        assertThat(row.getValue().getSourceSessionHash()).matches("^[0-9a-f]{64}$");
        assertThat(row.getValue().getNextAttemptAt()).isEqualTo(NOW);
        verify(audits).save(any(MeetingSessionErasureAudit.class));
    }

    @Test
    void localDeleteRechecksLegalHoldPredicateBeforeSessionCascade() {
        MeetingSessionErasure row = claimed(false);
        when(erasures.findClaimedForUpdate(SESSION, CLAIM)).thenReturn(Optional.of(row));
        when(analysisRuns.findErasureScopeForUpdate(MEETING, TENANT, SESSION.toString()))
                .thenReturn(List.of());
        when(analysisRuns.existsLegalHoldForErasure(MEETING, TENANT, SESSION.toString()))
                .thenReturn(true);
        when(erasures.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.eraseLocal(row, CLAIM);

        assertThat(result.status()).isEqualTo(
                MeetingSessionErasureService.LocalResult.Status.HELD);
        assertThat(row.getStatus()).isEqualTo(MeetingSessionErasureStatus.HELD);
        verify(sessions, never()).delete(any());
    }

    @Test
    void completeRequiresLocalSuccessAndClearsRawAlias() {
        MeetingSessionErasure notLocal = claimed(false);
        when(erasures.findClaimedForUpdate(SESSION, CLAIM)).thenReturn(Optional.of(notLocal));
        service.markComplete(SESSION, CLAIM, 3);
        verify(erasures, never()).saveAndFlush(any());

        MeetingSessionErasure local = claimed(true);
        when(erasures.findClaimedForUpdate(SESSION, CLAIM)).thenReturn(Optional.of(local));
        when(erasures.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service.markComplete(SESSION, CLAIM, 3);

        assertThat(local.getStatus()).isEqualTo(MeetingSessionErasureStatus.COMPLETE);
        assertThat(local.isRemoteErased()).isTrue();
        assertThat(local.getSourceSessionId()).isNull();
        assertThat(local.getCompletedAt()).isEqualTo(NOW);
        assertThat(local.getClaimToken()).isNull();
    }

    private static MeetingSession session() {
        MeetingSession row = new MeetingSession();
        ReflectionTestUtils.setField(row, "id", SESSION);
        row.setTenantId(TENANT);
        row.setOrgId(TENANT);
        row.setMeetingId(MEETING);
        row.setExternalSessionId("REC-42");
        return row;
    }

    private static MeetingSessionErasure claimed(boolean localErased) {
        MeetingSessionErasure row = new MeetingSessionErasure();
        row.setSessionId(SESSION);
        row.setTenantId(TENANT);
        row.setOrgId(TENANT);
        row.setMeetingId(MEETING);
        row.setSourceSessionId("REC-42");
        row.setSourceSessionHash(SessionAliasHasher.sha256("REC-42"));
        row.setStatus(MeetingSessionErasureStatus.ACTIVE);
        row.setLocalErased(localErased);
        row.setClaimToken(CLAIM);
        row.setClaimedAt(NOW);
        row.setLeaseExpiresAt(NOW.plusSeconds(60));
        row.setNextAttemptAt(NOW);
        row.setRequestedAt(NOW);
        row.setUpdatedAt(NOW);
        return row;
    }
}
