package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.MeetingActionCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingAgendaItemCreateRequest;
import com.example.meeting.dto.v1.admin.MeetingAgendaItemUpdateRequest;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingAgendaItem;
import com.example.meeting.model.MeetingAgendaItemStatus;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAgendaItemRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import com.example.meeting.security.AdminTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class MeetingAgendaLifecycleServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID AGENDA_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ACTION_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "organizer", "organizer");

    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final MeetingSessionRepository sessions = mock(MeetingSessionRepository.class);
    private final MeetingActionRepository actions = mock(MeetingActionRepository.class);
    private final MeetingAgendaItemRepository agenda = mock(MeetingAgendaItemRepository.class);
    private final MeetingDecisionRepository decisions = mock(MeetingDecisionRepository.class);
    private final AtomicReference<MeetingAgendaItem> savedAgenda = new AtomicReference<>();
    private final AtomicReference<MeetingAction> savedAction = new AtomicReference<>();
    private MeetingService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenFgaAuthzService> authz = mock(ObjectProvider.class);
        service = new MeetingService(
                meetings,
                sessions,
                actions,
                agenda,
                decisions,
                mock(MeetingEventOutboxRepository.class),
                mock(MeetingAnalysisRunRepository.class),
                mock(MeetingSessionErasureService.class),
                authz,
                false,
                false, userId -> java.util.Optional.empty());
        when(meetings.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(meeting()));
        when(agenda.save(org.mockito.ArgumentMatchers.any(MeetingAgendaItem.class)))
                .thenAnswer(invocation -> persistAgenda(invocation.getArgument(0)));
        // dilim-4: the service flushes the action (the @Version feeds the event key).
        when(actions.saveAndFlush(org.mockito.ArgumentMatchers.any(MeetingAction.class)))
                .thenAnswer(invocation -> persistAction(invocation.getArgument(0)));
    }

    @Test
    void organizerCreatesOrderedAgendaAndAssignedAction_thenSecondReadSeesBoth() {
        var agendaCreated = service.createAgendaItem(
                TENANT,
                MEETING_ID,
                new MeetingAgendaItemCreateRequest(
                        0, "Bütçe sapmaları", "Kritik kalemleri görüş", "finance-owner", 20));
        var actionCreated = service.createAction(
                TENANT,
                MEETING_ID,
                new MeetingActionCreateRequest(
                        "Revize tahmini paylaş", "finance-owner", null, Instant.parse("2026-08-05T12:00:00Z")));

        when(agenda.findByMeetingIdVisibleToOrg(MEETING_ID, TENANT_ID))
                .thenReturn(List.of(savedAgenda.get()));
        when(actions.findByMeetingIdVisibleToOrg(MEETING_ID, TENANT_ID))
                .thenReturn(List.of(savedAction.get()));

        assertThat(service.listAgendaItems(TENANT, MEETING_ID))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo(agendaCreated.id());
                    assertThat(item.position()).isZero();
                    assertThat(item.ownerSubject()).isEqualTo("finance-owner");
                    assertThat(item.status()).isEqualTo(MeetingAgendaItemStatus.PENDING);
                });
        assertThat(service.listActions(TENANT, MEETING_ID))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.id()).isEqualTo(actionCreated.id());
                    assertThat(action.assigneeSubject()).isEqualTo("finance-owner");
                    assertThat(action.dueAt()).isEqualTo(Instant.parse("2026-08-05T12:00:00Z"));
                });
    }

    @Test
    void foreignTenantCannotProbeAgendaChildren() {
        UUID foreignTenantId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        AdminTenantContext foreign = new AdminTenantContext(foreignTenantId, "foreign", "foreign");
        when(meetings.findVisibleToOrgAndId(foreignTenantId, MEETING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listAgendaItems(foreign, MEETING_ID))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));
        verifyNoInteractions(agenda);
    }

    @Test
    void staleAgendaUpdateReturnsConflictWithoutSaving() {
        MeetingAgendaItem item = agendaItem();
        ReflectionTestUtils.setField(item, "version", 3L);
        when(agenda.findByIdAndMeetingIdVisibleToOrg(AGENDA_ID, MEETING_ID, TENANT_ID))
                .thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service.updateAgendaItem(
                        TENANT,
                        MEETING_ID,
                        AGENDA_ID,
                        new MeetingAgendaItemUpdateRequest(
                                1,
                                "Yeni sıra",
                                null,
                                "owner",
                                10,
                                MeetingAgendaItemStatus.IN_PROGRESS,
                                2L)))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasMessageContaining("expectedVersion=2")
                .hasMessageContaining("currentVersion=3");
        verify(agenda, never()).save(any());
    }

    private MeetingAgendaItem persistAgenda(MeetingAgendaItem item) {
        if (item.getId() == null) {
            ReflectionTestUtils.setField(item, "id", AGENDA_ID);
            ReflectionTestUtils.setField(item, "createdAt", Instant.parse("2026-08-01T07:00:00Z"));
            ReflectionTestUtils.setField(item, "updatedAt", Instant.parse("2026-08-01T07:00:00Z"));
            ReflectionTestUtils.setField(item, "version", 0L);
        }
        savedAgenda.set(item);
        return item;
    }

    private MeetingAction persistAction(MeetingAction action) {
        if (action.getId() == null) {
            ReflectionTestUtils.setField(action, "id", ACTION_ID);
            ReflectionTestUtils.setField(action, "createdAt", Instant.parse("2026-08-01T07:00:00Z"));
            ReflectionTestUtils.setField(action, "updatedAt", Instant.parse("2026-08-01T07:00:00Z"));
            ReflectionTestUtils.setField(action, "version", 0L);
        }
        savedAction.set(action);
        return action;
    }

    private static Meeting meeting() {
        Meeting meeting = new Meeting();
        ReflectionTestUtils.setField(meeting, "id", MEETING_ID);
        meeting.setTenantId(TENANT_ID);
        meeting.setOrgId(TENANT_ID);
        return meeting;
    }

    private static MeetingAgendaItem agendaItem() {
        MeetingAgendaItem item = new MeetingAgendaItem();
        ReflectionTestUtils.setField(item, "id", AGENDA_ID);
        item.setMeetingId(MEETING_ID);
        item.setTenantId(TENANT_ID);
        item.setOrgId(TENANT_ID);
        item.setPosition(0);
        item.setTitle("Gündem");
        item.setStatus(MeetingAgendaItemStatus.PENDING);
        return item;
    }
}
