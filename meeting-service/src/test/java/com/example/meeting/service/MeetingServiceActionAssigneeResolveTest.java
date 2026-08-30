package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.MeetingActionCreateRequest;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAgendaItemRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import com.example.meeting.security.AdminTenantContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Faz 24 Görevler (gitops#3507) — server-side assignee resolution contract.
 *
 * <p>UIs send the public directory's numeric {@code assigneeUserId}; the
 * service must resolve it to the stable KC subject (never persist the mutable
 * numeric id), reject a request carrying both identity forms, and fail closed
 * (422) when the directory has no subject binding for the id.
 */
@ExtendWith(MockitoExtension.class)
class MeetingServiceActionAssigneeResolveTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "manager-sub", "manager-sub");

    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingSessionRepository sessionRepository;
    @Mock private MeetingActionRepository actionRepository;
    @Mock private MeetingAgendaItemRepository agendaItemRepository;
    @Mock private MeetingDecisionRepository decisionRepository;
    @Mock private MeetingEventOutboxRepository eventOutboxRepository;
    @Mock private MeetingAnalysisRunRepository analysisRunRepository;
    @Mock private MeetingSessionErasureService sessionErasureService;
    @Mock private ObjectProvider<OpenFgaAuthzService> authzProvider;
    @Mock private AssigneeDirectoryClient assigneeDirectoryClient;

    private MeetingService service() {
        return new MeetingService(
                meetingRepository,
                sessionRepository,
                actionRepository,
                agendaItemRepository,
                decisionRepository,
                eventOutboxRepository,
                analysisRunRepository,
                sessionErasureService,
                authzProvider,
                false,
                false,
                assigneeDirectoryClient);
    }

    private void stubMeeting() {
        Meeting meeting = new Meeting();
        org.springframework.test.util.ReflectionTestUtils.setField(meeting, "id", MEETING_ID);
        meeting.setTenantId(TENANT_ID);
        meeting.setOrgId(TENANT_ID);
        meeting.setTitle("test");
        meeting.setStatus(MeetingStatus.SCHEDULED);
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meeting));
    }

    @Test
    void createResolvesAssigneeUserIdToKcSubjectBeforePersisting() {
        stubMeeting();
        when(assigneeDirectoryClient.resolveKcSubject(42L))
                .thenReturn(Optional.of("kc-subject-42"));
        when(actionRepository.saveAndFlush(any(MeetingAction.class)))
                .thenAnswer(invocation -> {
                    MeetingAction a = invocation.getArgument(0);
                    // JPA would mint these on flush; the unit stub mirrors that so the
                    // dilim-4 outbox factory (which needs id + version) can build.
                    if (a.getId() == null) {
                        org.springframework.test.util.ReflectionTestUtils
                                .setField(a, "id", UUID.randomUUID());
                    }
                    return a;
                });

        service().createAction(TENANT, MEETING_ID,
                new MeetingActionCreateRequest("Raporu hazırla", null, 42L, null));

        // The persisted row carries the STABLE subject, never the numeric id.
        org.mockito.ArgumentCaptor<MeetingAction> saved =
                org.mockito.ArgumentCaptor.forClass(MeetingAction.class);
        org.mockito.Mockito.verify(actionRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getAssigneeSubject()).isEqualTo("kc-subject-42");
    }

    @Test
    void createRejectsBothIdentityFormsWith400() {
        stubMeeting();

        assertThatThrownBy(() -> service().createAction(TENANT, MEETING_ID,
                new MeetingActionCreateRequest("Raporu hazırla", "some-subject", 42L, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(assigneeDirectoryClient);
    }

    @Test
    void createFailsClosedWith422WhenDirectoryHasNoSubjectBinding() {
        stubMeeting();
        when(assigneeDirectoryClient.resolveKcSubject(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createAction(TENANT, MEETING_ID,
                new MeetingActionCreateRequest("Raporu hazırla", null, 42L, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode())
                                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void createMaps503WhenDirectoryUnavailableInsteadOfDroppingAssignment() {
        stubMeeting();
        when(assigneeDirectoryClient.resolveKcSubject(42L)).thenThrow(
                new AssigneeDirectoryClient.ResolutionUnavailableException("down"));

        assertThatThrownBy(() -> service().createAction(TENANT, MEETING_ID,
                new MeetingActionCreateRequest("Raporu hazırla", null, 42L, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void createWithPlainSubjectStaysBackwardCompatible() {
        stubMeeting();
        when(actionRepository.saveAndFlush(any(MeetingAction.class)))
                .thenAnswer(invocation -> {
                    MeetingAction a = invocation.getArgument(0);
                    // JPA would mint these on flush; the unit stub mirrors that so the
                    // dilim-4 outbox factory (which needs id + version) can build.
                    if (a.getId() == null) {
                        org.springframework.test.util.ReflectionTestUtils
                                .setField(a, "id", UUID.randomUUID());
                    }
                    return a;
                });

        service().createAction(TENANT, MEETING_ID,
                new MeetingActionCreateRequest("Raporu hazırla", "srv-subject", null, null));

        verifyNoInteractions(assigneeDirectoryClient);
    }
}
