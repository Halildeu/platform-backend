package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.MeetingResponse;
import com.example.meeting.dto.v1.admin.MeetingSearchCriteria;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import com.example.meeting.security.AdminTenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MeetingServiceSearchTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Mock private MeetingRepository meetingRepository;
    @Mock private MeetingSessionRepository sessionRepository;
    @Mock private MeetingActionRepository actionRepository;
    @Mock private MeetingDecisionRepository decisionRepository;
    @Mock private MeetingEventOutboxRepository eventOutboxRepository;
    @Mock private com.example.meeting.repository.MeetingAnalysisRunRepository analysisRunRepository;
    @Mock private MeetingSessionErasureService sessionErasureService;
    @Mock private ObjectProvider<OpenFgaAuthzService> authzProvider;

    private MeetingService service;

    @BeforeEach
    void setUp() {
        service = new MeetingService(
                meetingRepository,
                sessionRepository,
                actionRepository,
                decisionRepository,
                eventOutboxRepository,
                analysisRunRepository,
                sessionErasureService,
                authzProvider,
                false,
                false);
    }

    @Test
    void listMeetingsDelegatesEveryFilterWithTenantScopeAndMapsStartedAt() {
        AdminTenantContext tenant = new AdminTenantContext(TENANT_ID, "viewer", "viewer");
        MeetingSearchCriteria criteria = MeetingSearchCriteria.from(
                MeetingStatus.COMPLETED,
                "Roadmap",
                MEETING_ID,
                "2026-07-01T00:00:00Z",
                "2026-08-01T00:00:00Z");
        Pageable pageable = PageRequest.of(1, 25);
        Meeting meeting = meeting();
        when(meetingRepository.searchVisibleToOrg(
                TENANT_ID,
                true,
                MeetingStatus.COMPLETED,
                true,
                "Roadmap",
                true,
                MEETING_ID,
                true,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                pageable))
                .thenReturn(new PageImpl<>(List.of(meeting), pageable, 26));

        Page<MeetingResponse> result = service.listMeetings(tenant, criteria, pageable);

        assertThat(result.getContent()).singleElement().satisfies(response -> {
            assertThat(response.id()).isEqualTo(MEETING_ID);
            assertThat(response.orgId()).isEqualTo(TENANT_ID);
            assertThat(response.startedAt()).isEqualTo(Instant.parse("2026-07-18T09:05:00Z"));
        });
        verify(meetingRepository).searchVisibleToOrg(
                TENANT_ID,
                true,
                MeetingStatus.COMPLETED,
                true,
                "Roadmap",
                true,
                MEETING_ID,
                true,
                Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"),
                pageable);
    }

    private static Meeting meeting() {
        Meeting meeting = new Meeting();
        ReflectionTestUtils.setField(meeting, "id", MEETING_ID);
        ReflectionTestUtils.setField(meeting, "createdAt", Instant.parse("2026-07-18T08:00:00Z"));
        ReflectionTestUtils.setField(meeting, "updatedAt", Instant.parse("2026-07-18T10:00:00Z"));
        ReflectionTestUtils.setField(meeting, "version", 0L);
        meeting.setTenantId(TENANT_ID);
        meeting.setOrgId(TENANT_ID);
        meeting.setTitle("Roadmap review");
        meeting.setDescription("Description");
        meeting.setStatus(MeetingStatus.COMPLETED);
        meeting.setStartedAt(Instant.parse("2026-07-18T09:05:00Z"));
        meeting.setScheduledStart(Instant.parse("2026-07-18T09:00:00Z"));
        meeting.setScheduledEnd(Instant.parse("2026-07-18T10:00:00Z"));
        meeting.setOrganizerSubject("organizer");
        meeting.setCreatedBySubject("creator");
        meeting.setLastUpdatedBySubject("updater");
        return meeting;
    }
}
