package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.MeetingActionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingActionUpdateRequest;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingActionStatus;
import com.example.meeting.model.MeetingEventOutbox;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Faz 24 Görevler dilim-4 (gitops#3486) — manual assignment emits
 * {@code meeting.action.reassigned} on the ACTION aggregate.
 *
 * <p>The emit rule under test: a row is written exactly when a real NEW owner
 * appears (creation with an assignee, first assignment, hand-over). No event on
 * an assignee-preserving edit, none on clearing the assignee, none on an
 * unassigned creation — those are not notification facts.
 */
@ExtendWith(MockitoExtension.class)
class MeetingServiceActionAssignmentEventTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ACTION_ID =
            UUID.fromString("77777777-7777-4777-8777-777777777777");
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
        ReflectionTestUtils.setField(meeting, "id", MEETING_ID);
        meeting.setTenantId(TENANT_ID);
        meeting.setOrgId(TENANT_ID);
        meeting.setTitle("test");
        meeting.setStatus(MeetingStatus.SCHEDULED);
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meeting));
    }

    /** Mirrors what JPA mints on flush: an id and a bumped @Version. */
    private void stubSaveAndFlush(long flushedVersion) {
        when(actionRepository.saveAndFlush(any(MeetingAction.class)))
                .thenAnswer(invocation -> {
                    MeetingAction a = invocation.getArgument(0);
                    if (a.getId() == null) {
                        ReflectionTestUtils.setField(a, "id", ACTION_ID);
                    }
                    ReflectionTestUtils.setField(a, "version", flushedVersion);
                    return a;
                });
    }

    private MeetingAction existingAction(String assignee, long version) {
        MeetingAction action = new MeetingAction();
        ReflectionTestUtils.setField(action, "id", ACTION_ID);
        action.setMeetingId(MEETING_ID);
        action.setTenantId(TENANT_ID);
        action.setOrgId(TENANT_ID);
        action.setDescription("Raporu hazırla");
        action.setAssigneeSubject(assignee);
        action.setStatus(MeetingActionStatus.OPEN);
        action.setCreatedBySubject("manager-sub");
        action.setLastUpdatedBySubject("manager-sub");
        ReflectionTestUtils.setField(action, "version", version);
        when(actionRepository.findByIdAndMeetingIdVisibleToOrg(ACTION_ID, MEETING_ID, TENANT_ID))
                .thenReturn(Optional.of(action));
        return action;
    }

    @Test
    void createWithAssigneeEmitsReassignedEventKeyedOnActionAggregate() {
        stubMeeting();
        stubSaveAndFlush(0L);

        service().createAction(TENANT, MEETING_ID,
                new MeetingActionCreateRequest("Raporu hazırla", "kc-ali", null, null));

        ArgumentCaptor<MeetingEventOutbox> row = ArgumentCaptor.forClass(MeetingEventOutbox.class);
        verify(eventOutboxRepository).save(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo("meeting.action.reassigned");
        assertThat(row.getValue().getAggregateType()).isEqualTo("meeting.action");
        assertThat(row.getValue().getAggregateId()).isEqualTo(ACTION_ID);
        assertThat(row.getValue().getEventKey())
                .isEqualTo("meeting.action|" + ACTION_ID + "|meeting.action.reassigned|0");
        assertThat(row.getValue().getPayload())
                .contains("\"assigneeSubject\":\"kc-ali\"")
                .contains("\"previousAssigneeSubject\":null");
    }

    @Test
    void createWithoutAssigneeEmitsNothing() {
        stubMeeting();
        stubSaveAndFlush(0L);

        service().createAction(TENANT, MEETING_ID,
                new MeetingActionCreateRequest("Raporu hazırla", null, null, null));

        verify(eventOutboxRepository, never()).save(any());
    }

    @Test
    void handOverEmitsWithPreviousOwnerAndFlushedVersionInTheKey() {
        stubMeeting();
        existingAction("kc-ali", 3L);
        stubSaveAndFlush(4L);

        service().updateAction(TENANT, MEETING_ID, ACTION_ID,
                new MeetingActionUpdateRequest(
                        "Raporu hazırla", "kc-veli", null, MeetingActionStatus.OPEN, null, 3L));

        ArgumentCaptor<MeetingEventOutbox> row = ArgumentCaptor.forClass(MeetingEventOutbox.class);
        verify(eventOutboxRepository).save(row.capture());
        assertThat(row.getValue().getEventKey())
                .isEqualTo("meeting.action|" + ACTION_ID + "|meeting.action.reassigned|4");
        assertThat(row.getValue().getPayload())
                .contains("\"assigneeSubject\":\"kc-veli\"")
                .contains("\"previousAssigneeSubject\":\"kc-ali\"");
    }

    @Test
    void assigneePreservingEditEmitsNothing() {
        stubMeeting();
        existingAction("kc-ali", 3L);
        stubSaveAndFlush(4L);

        service().updateAction(TENANT, MEETING_ID, ACTION_ID,
                new MeetingActionUpdateRequest(
                        "Başlık düzeltildi", "kc-ali", null, MeetingActionStatus.DONE, null, 3L));

        verify(eventOutboxRepository, never()).save(any());
    }

    @Test
    void clearingTheAssigneeEmitsNothing() {
        stubMeeting();
        existingAction("kc-ali", 3L);
        stubSaveAndFlush(4L);

        service().updateAction(TENANT, MEETING_ID, ACTION_ID,
                new MeetingActionUpdateRequest(
                        "Raporu hazırla", null, null, MeetingActionStatus.OPEN, null, 3L));

        verify(eventOutboxRepository, never()).save(any());
    }
}
