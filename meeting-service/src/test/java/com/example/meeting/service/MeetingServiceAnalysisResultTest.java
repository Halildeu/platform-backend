package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.dto.v1.admin.MeetingAnalysisResultResponse;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingAnalysisRunStatus;
import com.example.meeting.model.MeetingSummary;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSessionRepository;
import com.example.meeting.repository.MeetingSummaryRepository;
import com.example.meeting.security.AdminTenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** #244 BE-1b — the read-path counterpart to BE-1's write-only ingestion endpoint. */
@ExtendWith(MockitoExtension.class)
class MeetingServiceAnalysisResultTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "user-3", "user-3");

    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private MeetingSessionRepository sessionRepository;
    @Mock
    private MeetingActionRepository actionRepository;
    @Mock
    private MeetingDecisionRepository decisionRepository;
    @Mock
    private MeetingAnalysisRunRepository analysisRunRepository;
    @Mock
    private MeetingSummaryRepository summaryRepository;
    @Mock
    private ObjectProvider<OpenFgaAuthzService> authzProvider;

    private MeetingService meetingService;

    @BeforeEach
    void setUp() {
        meetingService = new MeetingService(
                meetingRepository,
                sessionRepository,
                actionRepository,
                decisionRepository,
                analysisRunRepository,
                summaryRepository,
                authzProvider);
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID))
                .thenReturn(Optional.of(new Meeting()));
    }

    @Test
    void getAnalysisResult_returnsCanonicalRunSummary() {
        MeetingAnalysisRun run = new MeetingAnalysisRun();
        run.setMeetingId(MEETING_ID);
        run.setAnalysisRunId("run-1");
        run.setStatus(MeetingAnalysisRunStatus.CANONICAL);
        run.setAnalyzerContractVersion("5-adr0043");
        run.setModelVersion("llama3.1:8b");
        run.setPromptVersion("ollama-v1");
        run.setGeneratedAt(Instant.parse("2026-07-10T10:00:00Z"));
        UUID runId = UUID.randomUUID();
        setId(run, runId);

        MeetingSummary summary = new MeetingSummary();
        summary.setAnalysisRunId(runId);
        summary.setSummaryText("Butce onaylandi.");
        summary.setGroundingStatus("verified");

        when(analysisRunRepository.findByMeetingIdAndStatus(MEETING_ID, MeetingAnalysisRunStatus.CANONICAL))
                .thenReturn(Optional.of(run));
        when(summaryRepository.findByAnalysisRunId(runId)).thenReturn(Optional.of(summary));

        MeetingAnalysisResultResponse response = meetingService.getAnalysisResult(TENANT, MEETING_ID);

        assertThat(response.meetingId()).isEqualTo(MEETING_ID);
        assertThat(response.analysisRunId()).isEqualTo(runId);
        assertThat(response.status()).isEqualTo("CANONICAL");
        assertThat(response.summary()).isEqualTo("Butce onaylandi.");
        assertThat(response.groundingStatus()).isEqualTo("verified");
        assertThat(response.analyzerContractVersion()).isEqualTo("5-adr0043");
        assertThat(response.modelVersion()).isEqualTo("llama3.1:8b");
        assertThat(response.promptVersion()).isEqualTo("ollama-v1");
        assertThat(response.generatedAt()).isEqualTo(Instant.parse("2026-07-10T10:00:00Z"));
    }

    @Test
    void getAnalysisResult_noCanonicalRun_returns404() {
        when(analysisRunRepository.findByMeetingIdAndStatus(MEETING_ID, MeetingAnalysisRunStatus.CANONICAL))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingService.getAnalysisResult(TENANT, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void getAnalysisResult_unknownMeeting_returns404BeforeCheckingRun() {
        when(meetingRepository.findVisibleToOrgAndId(TENANT_ID, MEETING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingService.getAnalysisResult(TENANT, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** MeetingAnalysisRun.id is @GeneratedValue — set it via reflection for this fixture. */
    private static void setId(MeetingAnalysisRun run, UUID id) {
        try {
            var field = MeetingAnalysisRun.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(run, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
