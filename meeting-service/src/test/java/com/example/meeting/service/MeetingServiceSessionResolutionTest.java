package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.model.MeetingSession;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class MeetingServiceSessionResolutionTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    private final MeetingSessionRepository sessions = mock(MeetingSessionRepository.class);
    private MeetingService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenFgaAuthzService> authz = mock(ObjectProvider.class);
        service = new MeetingService(
                mock(MeetingRepository.class), sessions,
                mock(MeetingActionRepository.class), mock(MeetingDecisionRepository.class),
                mock(MeetingEventOutboxRepository.class), mock(MeetingAnalysisRunRepository.class),
                authz, false, false);
    }

    @Test
    void resolvesContentFreeCanonicalIdentityWithinExactScope() {
        MeetingSession row = session();
        when(sessions.findByExternalSessionIdVisibleToOrg(MEETING, "SES-42", TENANT))
                .thenReturn(Optional.of(row));

        var result = service.resolveSession(TENANT, MEETING, "SES-42");

        assertThat(result.tenantId()).isEqualTo(TENANT);
        assertThat(result.orgId()).isEqualTo(TENANT);
        assertThat(result.meetingId()).isEqualTo(MEETING);
        assertThat(result.sessionId()).isEqualTo(SESSION);
        assertThat(result.externalSessionId()).isEqualTo("SES-42");
    }

    @Test
    void foreignTenantUsesDifferentPredicateAndReceivesSameNotFound() {
        UUID foreignTenant = UUID.randomUUID();
        when(sessions.findByExternalSessionIdVisibleToOrg(MEETING, "SES-42", foreignTenant))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveSession(foreignTenant, MEETING, "SES-42"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));
        verify(sessions).findByExternalSessionIdVisibleToOrg(MEETING, "SES-42", foreignTenant);
    }

    private MeetingSession session() {
        MeetingSession row = new MeetingSession();
        ReflectionTestUtils.setField(row, "id", SESSION);
        row.setTenantId(TENANT);
        row.setOrgId(TENANT);
        row.setMeetingId(MEETING);
        row.setExternalSessionId("SES-42");
        return row;
    }
}
