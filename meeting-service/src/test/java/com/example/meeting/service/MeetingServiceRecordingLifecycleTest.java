package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.MeetingSessionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingSessionUpdateRequest;
import com.example.meeting.dto.v1.admin.MeetingUpdateRequest;
import com.example.meeting.dto.v1.admin.RecordingLifecycleResponse;
import com.example.meeting.dto.v1.admin.RecordingLifecycleSyncRequest;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingSession;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.model.MeetingEventOutbox;
import com.example.meeting.model.TranscriptStatus;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MeetingServiceRecordingLifecycleTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SESSION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "stable-user", "stable-user");
    private static final Instant STARTED_AT = Instant.parse("2026-07-17T08:43:20Z");
    private static final Instant ENDED_AT = Instant.parse("2026-07-17T08:44:20Z");

    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingSessionRepository sessionRepository;
    @Mock private MeetingActionRepository actionRepository;
    @Mock private MeetingDecisionRepository decisionRepository;
    @Mock private MeetingEventOutboxRepository eventOutboxRepository;
    @Mock private MeetingAnalysisRunRepository analysisRunRepository;
    @Mock private MeetingSessionErasureService sessionErasureService;
    @Mock private ObjectProvider<OpenFgaAuthzService> authzProvider;
    @Mock private OpenFgaAuthzService authzService;

    private MeetingService service;
    private Meeting meeting;
    private final AtomicReference<MeetingSession> savedSession = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        service = new MeetingService(
                meetingRepository, sessionRepository, actionRepository, decisionRepository,
                eventOutboxRepository, analysisRunRepository, sessionErasureService,
                authzProvider, false, false);
        meeting = meeting(MeetingStatus.SCHEDULED);
        lenient().when(meetingRepository.findVisibleToOrgAndIdForUpdate(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meeting));
        lenient().when(authzProvider.getIfAvailable()).thenReturn(authzService);
        lenient().when(authzService.isEnabled()).thenReturn(true);
        lenient().when(authzService.checkPrincipal(
                        "user:stable-user",
                        MeetingAuthz.CAN_RECORD,
                        MeetingAuthz.OBJECT_TYPE,
                        MEETING_ID.toString()))
                .thenReturn(true);
        lenient().when(sessionRepository.saveAndFlush(any(MeetingSession.class))).thenAnswer(invocation -> {
            MeetingSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                ReflectionTestUtils.setField(session, "id", SESSION_ID);
            }
            savedSession.set(session);
            return session;
        });
        lenient().when(meetingRepository.saveAndFlush(any(Meeting.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(sessionRepository.findByMeetingIdVisibleToOrg(MEETING_ID, TENANT_ID))
                .thenAnswer(ignored -> savedSession.get() == null ? List.of() : List.of(savedSession.get()));
        lenient().when(sessionRepository.findEarliestStartedAtVisibleToOrg(MEETING_ID, TENANT_ID))
                .thenAnswer(ignored -> Optional.ofNullable(savedSession.get())
                        .map(MeetingSession::getStartedAt));
    }

    @Test
    void startThenFinishCreatesOneMonotonicCanonicalSession() {
        when(sessionRepository.findByExternalSessionIdVisibleToOrg(MEETING_ID, "SES-1", TENANT_ID))
                .thenReturn(Optional.empty());

        RecordingLifecycleResponse started = service.syncRecordingLifecycle(
                TENANT, MEETING_ID, new RecordingLifecycleSyncRequest("SES-1", STARTED_AT, null));

        MeetingSession session = savedSession.get();
        assertThat(started.meetingStatus()).isEqualTo(MeetingStatus.IN_PROGRESS);
        assertThat(started.transcriptStatus()).isEqualTo(TranscriptStatus.PENDING);
        assertThat(session.getExternalSessionId()).isEqualTo("SES-1");
        assertThat(session.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(session.getEndedAt()).isNull();
        assertThat(meeting.getStartedAt())
                .as("the first attended recording replaces the scheduled history key")
                .isEqualTo(STARTED_AT);

        when(sessionRepository.findByExternalSessionIdVisibleToOrg(MEETING_ID, "SES-1", TENANT_ID))
                .thenReturn(Optional.of(session));
        RecordingLifecycleResponse finished = service.syncRecordingLifecycle(
                TENANT, MEETING_ID, new RecordingLifecycleSyncRequest("SES-1", STARTED_AT, ENDED_AT));

        assertThat(finished.meetingStatus()).isEqualTo(MeetingStatus.COMPLETED);
        assertThat(finished.transcriptStatus()).isEqualTo(TranscriptStatus.PROCESSING);
        assertThat(finished.endedAt()).isEqualTo(ENDED_AT);
        assertThat(meeting.getScheduledEnd()).isNull();

        ArgumentCaptor<MeetingEventOutbox> outbox = ArgumentCaptor.forClass(MeetingEventOutbox.class);
        verify(eventOutboxRepository).saveAndFlush(outbox.capture());
        assertThat(outbox.getValue().getEventKey()).isEqualTo(
                "meeting.recording|" + SESSION_ID + "|meeting.recording.finished|1");
        assertThat(outbox.getValue().getPayload())
                .contains("\"recordingSessionId\":\"" + SESSION_ID + "\"")
                .contains("\"externalSessionId\":\"SES-1\"")
                .contains("\"finishedAt\":\"" + ENDED_AT + "\"")
                .doesNotContain("recordingUri", "audio", "transcriptText");
    }

    @Test
    void finishedSessionCannotBeRegressedOrRetimedByReplay() {
        MeetingSession session = session(ENDED_AT);
        session.setTranscriptStatus(TranscriptStatus.PROCESSING);
        savedSession.set(session);
        meeting.setStatus(MeetingStatus.COMPLETED);
        when(sessionRepository.findByExternalSessionIdVisibleToOrg(MEETING_ID, "SES-1", TENANT_ID))
                .thenReturn(Optional.of(session));

        RecordingLifecycleResponse replayedStart = service.syncRecordingLifecycle(
                TENANT, MEETING_ID, new RecordingLifecycleSyncRequest("SES-1", STARTED_AT.minusSeconds(5), null));
        RecordingLifecycleResponse replayedFinish = service.syncRecordingLifecycle(
                TENANT, MEETING_ID,
                new RecordingLifecycleSyncRequest("SES-1", STARTED_AT, ENDED_AT.plusSeconds(30)));

        assertThat(replayedStart.meetingStatus()).isEqualTo(MeetingStatus.COMPLETED);
        assertThat(replayedFinish.endedAt()).isEqualTo(ENDED_AT);
        assertThat(session.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(session.getEndedAt()).isEqualTo(ENDED_AT);
        verify(eventOutboxRepository, never()).saveAndFlush(any());
    }

    @Test
    void genericSessionUpdateCannotCreateClearOrRetimeRecordingEnd() {
        MeetingSession active = session(null);
        MeetingSession finished = session(ENDED_AT);
        when(sessionRepository.findByIdAndMeetingIdVisibleToOrg(SESSION_ID, MEETING_ID, TENANT_ID))
                .thenReturn(Optional.of(active), Optional.of(finished), Optional.of(finished));

        assertImmutableUpdateRejected(active, ENDED_AT);
        assertImmutableUpdateRejected(finished, ENDED_AT.plusSeconds(30));
        assertImmutableUpdateRejected(finished, null);

        verify(sessionRepository, never()).save(any());
        verify(eventOutboxRepository, never()).saveAndFlush(any());
    }

    @Test
    void genericSessionCreateCannotBypassRecordingLifecycleWithEndedAt() {
        assertThatThrownBy(() -> service.createSession(
                TENANT,
                MEETING_ID,
                new MeetingSessionCreateRequest("manual", STARTED_AT, ENDED_AT, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(sessionRepository, never()).save(any());
        verify(eventOutboxRepository, never()).saveAndFlush(any());
    }

    @Test
    void genericSessionCreateSynchronizesCanonicalHistoryStart() {
        Instant scheduledFallback = STARTED_AT.minusSeconds(3600);
        meeting.setScheduledStart(scheduledFallback);
        meeting.setStartedAt(scheduledFallback);

        service.createSession(
                TENANT,
                MEETING_ID,
                new MeetingSessionCreateRequest("manual", STARTED_AT, null, null));

        assertThat(meeting.getStartedAt()).isEqualTo(STARTED_AT);
        verify(meetingRepository).saveAndFlush(meeting);
    }

    @Test
    void genericSessionUpdateCanMoveCanonicalHistoryStartEarlier() {
        MeetingSession active = session(null);
        savedSession.set(active);
        when(sessionRepository.findByIdAndMeetingIdVisibleToOrg(SESSION_ID, MEETING_ID, TENANT_ID))
                .thenReturn(Optional.of(active));
        Instant earlierStart = STARTED_AT.minusSeconds(120);

        service.updateSession(
                TENANT,
                MEETING_ID,
                SESSION_ID,
                new MeetingSessionUpdateRequest(
                        active.getSessionLabel(),
                        earlierStart,
                        null,
                        active.getRecordingUri(),
                        active.getTranscriptStatus(),
                        null));

        assertThat(meeting.getStartedAt()).isEqualTo(earlierStart);
        verify(meetingRepository).saveAndFlush(meeting);
    }

    @Test
    void genericSessionDeleteRestoresScheduledHistoryFallback() {
        MeetingSession active = session(null);
        savedSession.set(active);
        Instant scheduledFallback = STARTED_AT.minusSeconds(3600);
        meeting.setScheduledStart(scheduledFallback);
        meeting.setStartedAt(STARTED_AT);
        when(sessionRepository.findByIdAndMeetingIdVisibleToOrg(SESSION_ID, MEETING_ID, TENANT_ID))
                .thenReturn(Optional.of(active));
        doAnswer(invocation -> {
            savedSession.set(null);
            return null;
        }).when(sessionRepository).delete(active);

        service.deleteSession(TENANT, MEETING_ID, SESSION_ID);

        assertThat(meeting.getStartedAt()).isEqualTo(scheduledFallback);
        verify(sessionRepository).flush();
        verify(meetingRepository).saveAndFlush(meeting);
    }

    @Test
    void meetingUpdateUsesParentLockAndPreservesEarliestSessionStart() {
        MeetingSession active = session(null);
        savedSession.set(active);
        Instant rescheduledStart = STARTED_AT.plusSeconds(3600);
        when(meetingRepository.save(meeting)).thenReturn(meeting);

        service.updateMeeting(
                TENANT,
                MEETING_ID,
                new MeetingUpdateRequest(
                        meeting.getTitle(),
                        null,
                        MeetingStatus.SCHEDULED,
                        meeting.getOrganizerSubject(),
                        rescheduledStart,
                        null,
                        null));

        assertThat(meeting.getStartedAt()).isEqualTo(STARTED_AT);
        verify(meetingRepository).findVisibleToOrgAndIdForUpdate(TENANT_ID, MEETING_ID);
    }

    @Test
    void finishCannotPrecedeThePersistedCanonicalStart() {
        MeetingSession session = session(null);
        savedSession.set(session);
        meeting.setStatus(MeetingStatus.IN_PROGRESS);
        when(sessionRepository.findByExternalSessionIdVisibleToOrg(MEETING_ID, "SES-1", TENANT_ID))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.syncRecordingLifecycle(
                TENANT,
                MEETING_ID,
                new RecordingLifecycleSyncRequest(
                        "SES-1", STARTED_AT.minusSeconds(30), STARTED_AT.minusSeconds(1))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(session.getEndedAt()).isNull();
        verify(sessionRepository, never()).saveAndFlush(any());
        verify(meetingRepository, never()).saveAndFlush(any());
        verify(eventOutboxRepository, never()).saveAndFlush(any());
    }

    @Test
    void pureReplayDoesNotWriteOrBumpAggregateVersions() {
        MeetingSession session = session(ENDED_AT);
        session.setTranscriptStatus(TranscriptStatus.PROCESSING);
        savedSession.set(session);
        meeting.setStatus(MeetingStatus.COMPLETED);
        when(sessionRepository.findByExternalSessionIdVisibleToOrg(MEETING_ID, "SES-1", TENANT_ID))
                .thenReturn(Optional.of(session));

        RecordingLifecycleResponse response = service.syncRecordingLifecycle(
                TENANT, MEETING_ID, new RecordingLifecycleSyncRequest("SES-1", STARTED_AT, ENDED_AT));

        assertThat(response.meetingStatus()).isEqualTo(MeetingStatus.COMPLETED);
        verify(sessionRepository, never()).saveAndFlush(any());
        verify(meetingRepository, never()).saveAndFlush(any());
        verify(eventOutboxRepository, never()).saveAndFlush(any());
    }

    @Test
    void finishingOneOfTwoActiveSessionsKeepsMeetingInProgress() {
        MeetingSession finishingSession = session(null);
        MeetingSession otherActiveSession = session(null);
        ReflectionTestUtils.setField(otherActiveSession, "id", UUID.randomUUID());
        otherActiveSession.setExternalSessionId("SES-2");
        savedSession.set(finishingSession);
        meeting.setStatus(MeetingStatus.IN_PROGRESS);
        when(sessionRepository.findByExternalSessionIdVisibleToOrg(MEETING_ID, "SES-1", TENANT_ID))
                .thenReturn(Optional.of(finishingSession));
        when(sessionRepository.findByMeetingIdVisibleToOrg(MEETING_ID, TENANT_ID))
                .thenReturn(List.of(finishingSession, otherActiveSession));

        RecordingLifecycleResponse response = service.syncRecordingLifecycle(
                TENANT, MEETING_ID, new RecordingLifecycleSyncRequest("SES-1", STARTED_AT, ENDED_AT));

        assertThat(response.meetingStatus()).isEqualTo(MeetingStatus.IN_PROGRESS);
        assertThat(finishingSession.getEndedAt()).isEqualTo(ENDED_AT);
        assertThat(finishingSession.getTranscriptStatus()).isEqualTo(TranscriptStatus.PROCESSING);
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void unstartedPlaceholderDoesNotKeepFinishedRecordingInProgress() {
        MeetingSession finishingSession = session(null);
        MeetingSession placeholder = session(null);
        ReflectionTestUtils.setField(placeholder, "id", UUID.randomUUID());
        placeholder.setExternalSessionId(null);
        placeholder.setStartedAt(null);
        placeholder.setSessionLabel("Future placeholder");
        savedSession.set(finishingSession);
        meeting.setStatus(MeetingStatus.IN_PROGRESS);
        when(sessionRepository.findByExternalSessionIdVisibleToOrg(MEETING_ID, "SES-1", TENANT_ID))
                .thenReturn(Optional.of(finishingSession));
        when(sessionRepository.findByMeetingIdVisibleToOrg(MEETING_ID, TENANT_ID))
                .thenReturn(List.of(finishingSession, placeholder));

        RecordingLifecycleResponse response = service.syncRecordingLifecycle(
                TENANT, MEETING_ID, new RecordingLifecycleSyncRequest("SES-1", STARTED_AT, ENDED_AT));

        assertThat(response.meetingStatus()).isEqualTo(MeetingStatus.COMPLETED);
        verify(meetingRepository).saveAndFlush(meeting);
    }

    @Test
    void canRecordIsCheckedAfterParentLockAndBeforeSessionMutation() {
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);
        when(authzService.checkPrincipal(
                "user:stable-user",
                MeetingAuthz.CAN_RECORD,
                MeetingAuthz.OBJECT_TYPE,
                MEETING_ID.toString()))
                .thenReturn(true);
        when(sessionRepository.findByExternalSessionIdVisibleToOrg(MEETING_ID, "SES-1", TENANT_ID))
                .thenReturn(Optional.empty());

        service.syncRecordingLifecycle(
                TENANT, MEETING_ID, new RecordingLifecycleSyncRequest("SES-1", STARTED_AT, null));

        InOrder order = inOrder(meetingRepository, authzService, sessionRepository);
        order.verify(meetingRepository).findVisibleToOrgAndIdForUpdate(TENANT_ID, MEETING_ID);
        order.verify(authzService).checkPrincipal(
                "user:stable-user",
                MeetingAuthz.CAN_RECORD,
                MeetingAuthz.OBJECT_TYPE,
                MEETING_ID.toString());
        order.verify(sessionRepository)
                .findByExternalSessionIdVisibleToOrg(MEETING_ID, "SES-1", TENANT_ID);
        order.verify(sessionRepository).saveAndFlush(any(MeetingSession.class));
    }

    @Test
    void deniedCanRecordFailsAfterParentLockAndBeforeSessionMutation() {
        when(authzProvider.getIfAvailable()).thenReturn(authzService);
        when(authzService.isEnabled()).thenReturn(true);
        when(authzService.checkPrincipal(
                "user:stable-user",
                MeetingAuthz.CAN_RECORD,
                MeetingAuthz.OBJECT_TYPE,
                MEETING_ID.toString()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.syncRecordingLifecycle(
                TENANT, MEETING_ID, new RecordingLifecycleSyncRequest("SES-1", STARTED_AT, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(meetingRepository).findVisibleToOrgAndIdForUpdate(TENANT_ID, MEETING_ID);
        verify(sessionRepository, never())
                .findByExternalSessionIdVisibleToOrg(MEETING_ID, "SES-1", TENANT_ID);
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void aNewRecordingSessionCanMoveACompletedMeetingBackToInProgress() {
        meeting.setStatus(MeetingStatus.COMPLETED);
        meeting.setStartedAt(STARTED_AT.minusSeconds(3600));
        when(sessionRepository.findByExternalSessionIdVisibleToOrg(MEETING_ID, "SES-2", TENANT_ID))
                .thenReturn(Optional.empty());

        RecordingLifecycleResponse response = service.syncRecordingLifecycle(
                TENANT, MEETING_ID, new RecordingLifecycleSyncRequest("SES-2", STARTED_AT, null));

        assertThat(response.meetingStatus()).isEqualTo(MeetingStatus.IN_PROGRESS);
        assertThat(savedSession.get().getExternalSessionId()).isEqualTo("SES-2");
        assertThat(meeting.getStartedAt()).isEqualTo(STARTED_AT);
        verify(meetingRepository).saveAndFlush(meeting);
    }

    @Test
    void cancelledMeetingFailsBeforeSessionMutation() {
        meeting.setStatus(MeetingStatus.CANCELLED);

        assertThatThrownBy(() -> service.syncRecordingLifecycle(
                TENANT, MEETING_ID, new RecordingLifecycleSyncRequest("SES-1", STARTED_AT, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void deleteSessionQueuesDurableCrossServiceErasure() {
        service.deleteSession(TENANT, MEETING_ID, SESSION_ID);

        verify(sessionErasureService).request(TENANT, MEETING_ID, SESSION_ID);
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void deleteSessionIsIdempotentlyDelegatedToLedger() {
        service.deleteSession(TENANT, MEETING_ID, SESSION_ID);
        service.deleteSession(TENANT, MEETING_ID, SESSION_ID);

        verify(sessionErasureService, org.mockito.Mockito.times(2))
                .request(TENANT, MEETING_ID, SESSION_ID);
    }

    private static Meeting meeting(MeetingStatus status) {
        Meeting value = new Meeting();
        ReflectionTestUtils.setField(value, "id", MEETING_ID);
        value.setTenantId(TENANT_ID);
        value.setOrgId(TENANT_ID);
        value.setTitle("Attended recording");
        value.setStatus(status);
        value.setStartedAt(STARTED_AT);
        value.setScheduledStart(STARTED_AT.minusSeconds(3600));
        ReflectionTestUtils.setField(value, "createdAt", STARTED_AT.minusSeconds(7200));
        value.setOrganizerSubject("stable-user");
        value.setCreatedBySubject("stable-user");
        value.setLastUpdatedBySubject("stable-user");
        return value;
    }

    private static MeetingSession session(Instant endedAt) {
        MeetingSession value = new MeetingSession();
        ReflectionTestUtils.setField(value, "id", SESSION_ID);
        value.setMeetingId(MEETING_ID);
        value.setTenantId(TENANT_ID);
        value.setOrgId(TENANT_ID);
        value.setSessionLabel("SES-1");
        value.setExternalSessionId("SES-1");
        value.setStartedAt(STARTED_AT);
        value.setEndedAt(endedAt);
        value.setTranscriptStatus(TranscriptStatus.PENDING);
        value.setCreatedBySubject("stable-user");
        value.setLastUpdatedBySubject("stable-user");
        return value;
    }

    private void assertImmutableUpdateRejected(MeetingSession session, Instant requestedEndedAt) {
        assertThatThrownBy(() -> service.updateSession(
                TENANT,
                MEETING_ID,
                SESSION_ID,
                new MeetingSessionUpdateRequest(
                        session.getSessionLabel(),
                        session.getStartedAt(),
                        requestedEndedAt,
                        session.getRecordingUri(),
                        session.getTranscriptStatus(),
                        null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}
