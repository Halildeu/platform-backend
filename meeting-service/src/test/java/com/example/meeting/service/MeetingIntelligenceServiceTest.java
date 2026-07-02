package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.meeting.config.MeetingAiProperties;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceAnalyzeRequest;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceAnalyzeResponse;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceTranscriptSegment;
import com.example.meeting.model.Meeting;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.security.AdminTenantContext;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MeetingIntelligenceServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Mock
    private MeetingRepository meetingRepository;

    @Mock
    private MeetingAiClient meetingAiClient;

    private MeetingIntelligenceService service;

    private final AdminTenantContext tenant =
            new AdminTenantContext(TENANT_ID, "admin@example.com", "admin@example.com");

    @BeforeEach
    void setUp() {
        service = new MeetingIntelligenceService(
                meetingRepository,
                meetingAiClient,
                new MeetingAiProperties(true, URI.create("http://meeting-ai"), "/analyze", Duration.ofSeconds(30), 128));
    }

    @Test
    void analyze_requiresVisibleMeetingThenCallsMeetingAi() {
        MeetingIntelligenceTranscriptSegment segment =
                new MeetingIntelligenceTranscriptSegment("Merhaba.", 0.0, 1.0);
        MeetingIntelligenceAnalyzeRequest request =
                new MeetingIntelligenceAnalyzeRequest(MEETING_ID, "SES-1", "  Merhaba.  ", null, List.of(segment));
        MeetingIntelligenceAnalyzeResponse upstream =
                new MeetingIntelligenceAnalyzeResponse(
                        "5-adr0043", "verified_only", "Ozet", "verified",
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        0, true, 0, "mock", "meeting-ai-test", 12,
                        null, null, null, null);

        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(new Meeting()));
        when(meetingAiClient.analyze(MEETING_ID, "SES-1", "Merhaba.", List.of(segment)))
                .thenReturn(upstream);

        MeetingIntelligenceAnalyzeResponse result = service.analyze(tenant, MEETING_ID, request);

        assertThat(result.meetingId()).isEqualTo(MEETING_ID);
        assertThat(result.sessionId()).isEqualTo("SES-1");
        assertThat(result.persisted()).isFalse();
        assertThat(result.storageMode()).isEqualTo("preview");
        verify(meetingRepository).findVisibleToOrgAndId(TENANT_ID, MEETING_ID);
        verify(meetingAiClient).analyze(MEETING_ID, "SES-1", "Merhaba.", List.of(segment));
    }

    @Test
    void analyze_rejectsMismatchedBodyMeetingIdBeforeLookup() {
        MeetingIntelligenceAnalyzeRequest request =
                new MeetingIntelligenceAnalyzeRequest(UUID.randomUUID(), "SES-1", "Merhaba", null, List.of());

        assertThatThrownBy(() -> service.analyze(tenant, MEETING_ID, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(meetingRepository, meetingAiClient);
    }

    @Test
    void analyze_rejectsOversizedTranscriptBeforeLookup() {
        MeetingIntelligenceAnalyzeRequest request =
                new MeetingIntelligenceAnalyzeRequest(MEETING_ID, "SES-1", "x".repeat(129), null, List.of());

        assertThatThrownBy(() -> service.analyze(tenant, MEETING_ID, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));

        verifyNoInteractions(meetingRepository, meetingAiClient);
    }

    @Test
    void analyze_unknownMeetingDoesNotCallMeetingAi() {
        MeetingIntelligenceAnalyzeRequest request =
                new MeetingIntelligenceAnalyzeRequest(MEETING_ID, "SES-1", "Merhaba", null, List.of());
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.analyze(tenant, MEETING_ID, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        verifyNoInteractions(meetingAiClient);
    }
}
