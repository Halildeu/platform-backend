package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.AssigneeCandidateResponse;
import com.example.meeting.dto.v1.admin.AssigneeCandidateSearchRequest;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAgendaItemRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.service.AssigneeDirectoryClient.AssigneeCandidate;
import java.util.List;
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
 * Faz 24 (gitops#3834) — the people picker behind "Göreve ata" for non-admin users.
 *
 * <p>The search runs for the signed-in REQUESTER (the token's {@code sub}), only through a meeting
 * the caller's org can see, and every failure is reported as what it is — a deny as 403, an outage
 * as 503 — never as an empty list.
 */
@ExtendWith(MockitoExtension.class)
class MeetingServiceAssigneeCandidatesTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "kc-requester", "4");

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

    private void stubVisibleMeeting() {
        Meeting meeting = new Meeting();
        org.springframework.test.util.ReflectionTestUtils.setField(meeting, "id", MEETING_ID);
        meeting.setTenantId(TENANT_ID);
        meeting.setOrgId(TENANT_ID);
        meeting.setTitle("test");
        meeting.setStatus(MeetingStatus.SCHEDULED);
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID)).thenReturn(Optional.of(meeting));
    }

    @Test
    void searchesForTheRequesterWithTrimmedTextAndDefaultPage() {
        stubVisibleMeeting();
        when(assigneeDirectoryClient.searchCandidates("kc-requester", "sevil", 10))
                .thenReturn(List.of(new AssigneeCandidate(7L, "Sevil Kaya", "sevil.kaya@acik.com")));

        var answer = service().searchAssigneeCandidates(
                TENANT, MEETING_ID, new AssigneeCandidateSearchRequest("  sevil ", null));

        assertThat(answer.items())
                .containsExactly(new AssigneeCandidateResponse(7L, "Sevil Kaya", "sevil.kaya@acik.com"));
        verify(assigneeDirectoryClient).searchCandidates("kc-requester", "sevil", 10);
    }

    @Test
    void meetingOutsideTheCallersOrgIs404WithoutTouchingTheDirectory() {
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().searchAssigneeCandidates(
                TENANT, MEETING_ID, new AssigneeCandidateSearchRequest("sevil", 5)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(assigneeDirectoryClient);
    }

    @Test
    void textShorterThanTwoCharactersAfterTrimIs400() {
        stubVisibleMeeting();

        assertThatThrownBy(() -> service().searchAssigneeCandidates(
                TENANT, MEETING_ID, new AssigneeCandidateSearchRequest(" s ", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(assigneeDirectoryClient);
    }

    @Test
    void requesterOutsideTheDirectoryIs403() {
        stubVisibleMeeting();
        when(assigneeDirectoryClient.searchCandidates("kc-requester", "sevil", 10))
                .thenThrow(new AssigneeDirectoryClient.DirectoryAccessDeniedException("no"));

        assertThatThrownBy(() -> service().searchAssigneeCandidates(
                TENANT, MEETING_ID, new AssigneeCandidateSearchRequest("sevil", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void directoryOutageIs503NotAnEmptyPicker() {
        stubVisibleMeeting();
        when(assigneeDirectoryClient.searchCandidates("kc-requester", "sevil", 10))
                .thenThrow(new AssigneeDirectoryClient.ResolutionUnavailableException("down"));

        assertThatThrownBy(() -> service().searchAssigneeCandidates(
                TENANT, MEETING_ID, new AssigneeCandidateSearchRequest("sevil", null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
