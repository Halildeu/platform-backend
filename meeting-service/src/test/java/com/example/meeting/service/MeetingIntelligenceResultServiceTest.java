package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.example.meeting.dto.v1.admin.MeetingIntelligenceResultResponse;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.security.AdminTenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
class MeetingIntelligenceResultServiceTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RUN_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private MeetingAnalysisRunRepository runRepository;
    @Mock
    private MeetingDecisionRepository decisionRepository;
    @Mock
    private MeetingActionRepository actionRepository;
    @Mock
    private MeetingIntelligenceResultAccessAuditService accessAuditService;

    private MeetingIntelligenceResultService service;
    private final AdminTenantContext tenant =
            new AdminTenantContext(ORG_ID, "admin@example.com", "admin@example.com");

    @BeforeEach
    void setUp() {
        service = new MeetingIntelligenceResultService(
                meetingRepository,
                runRepository,
                decisionRepository,
                actionRepository,
                accessAuditService,
                new ObjectMapper());
    }

    @Test
    void getLatest_returnsOneRunBoundTypedAggregate() {
        MeetingAnalysisRun run = analysisRun();
        run.setSummaryCitations(run.getSummaryCitations().replace("a".repeat(64), "A".repeat(64)));
        MeetingDecision decision = new MeetingDecision();
        decision.setTitle("Kisa karar");
        decision.setDetail("Butce artisi onaylandi.");
        MeetingAction action = new MeetingAction();
        action.setDescription("Raporu yayinla.");
        action.setAssigneeSubject("user-42");
        action.setDueAt(Instant.parse("2026-07-14T09:00:00Z"));

        when(meetingRepository.findVisibleToOrgAndId(ORG_ID, MEETING_ID))
                .thenReturn(Optional.of(new Meeting()));
        when(runRepository.findLatestByMeetingIdVisibleToOrg(MEETING_ID, ORG_ID))
                .thenReturn(Optional.of(run));
        when(decisionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID)).thenReturn(List.of(decision));
        when(actionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID)).thenReturn(List.of(action));

        MeetingIntelligenceResultResponse result = service.getLatest(tenant, MEETING_ID);

        assertThat(result.analysisRunId()).isEqualTo(RUN_ID);
        assertThat(result.summary()).isEqualTo("Dogrulanmis ozet.");
        assertThat(result.decisions()).containsExactly("Butce artisi onaylandi.");
        assertThat(result.actionItems()).singleElement().satisfies(item -> {
            assertThat(item.text()).isEqualTo("Raporu yayinla.");
            assertThat(item.owner()).isEqualTo("user-42");
            assertThat(item.dueDate()).isEqualTo("2026-07-14T09:00:00Z");
        });
        assertThat(result.summaryCitations()).hasSize(1);
        assertThat(result.rejectedClaims()).hasSize(1);
        assertThat(result.persisted()).isTrue();
        assertThat(result.storageMode()).isEqualTo("canonical");

        verify(decisionRepository).findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID);
        verify(actionRepository).findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID);
        verify(accessAuditService).recordCanonicalRead(tenant, MEETING_ID, RUN_ID);
    }

    @Test
    void getLatest_unknownOrForeignMeeting_doesNotProbeAnalysisTables() {
        when(meetingRepository.findVisibleToOrgAndId(ORG_ID, MEETING_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLatest(tenant, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getReason()).isEqualTo("MEETING_NOT_FOUND");
                });

        verifyNoInteractions(runRepository, decisionRepository, actionRepository, accessAuditService);
    }

    @Test
    void getLatest_visibleMeetingWithoutAnalysis_hasStableNotReadySemantic() {
        when(meetingRepository.findVisibleToOrgAndId(ORG_ID, MEETING_ID))
                .thenReturn(Optional.of(new Meeting()));
        when(runRepository.findLatestByMeetingIdVisibleToOrg(MEETING_ID, ORG_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLatest(tenant, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getReason()).isEqualTo("ANALYSIS_RESULT_NOT_FOUND");
                });

        verifyNoInteractions(decisionRepository, actionRepository);
        verifyNoInteractions(accessAuditService);
    }

    @Test
    void getLatest_auditWriteFailure_preventsDisclosure() {
        MeetingAnalysisRun run = analysisRun();
        when(meetingRepository.findVisibleToOrgAndId(ORG_ID, MEETING_ID))
                .thenReturn(Optional.of(new Meeting()));
        when(runRepository.findLatestByMeetingIdVisibleToOrg(MEETING_ID, ORG_ID))
                .thenReturn(Optional.of(run));
        when(decisionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID)).thenReturn(List.of());
        when(actionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID)).thenReturn(List.of());
        doThrow(new IllegalStateException("audit unavailable"))
                .when(accessAuditService).recordCanonicalRead(tenant, MEETING_ID, RUN_ID);

        assertThatThrownBy(() -> service.getLatest(tenant, MEETING_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
    }

    @Test
    void getLatest_malformedEvidence_failsClosedWithoutReturningPartialResult() {
        MeetingAnalysisRun run = analysisRun();
        run.setSummaryCitations("{not-json");
        when(meetingRepository.findVisibleToOrgAndId(ORG_ID, MEETING_ID))
                .thenReturn(Optional.of(new Meeting()));
        when(runRepository.findLatestByMeetingIdVisibleToOrg(MEETING_ID, ORG_ID))
                .thenReturn(Optional.of(run));
        when(decisionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID)).thenReturn(List.of());
        when(actionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getLatest(tenant, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(exception.getReason()).isEqualTo("ANALYSIS_RESULT_INVALID");
                    assertThat(exception.getCause()).isNull();
                })
                .hasMessageNotContaining("not-json");

        verifyNoInteractions(accessAuditService);
    }

    @Test
    void getLatest_structurallyInvalidEvidence_failsClosed() {
        MeetingAnalysisRun run = analysisRun();
        run.setSummaryCitations("""
                [{"claim":null,"source_index":0,"source_text":"evidence",
                  "similarity":0.9,"grounded":true,"status":"PASSED","reason":"",
                  "source_char_start":0,"source_char_end":8,
                  "source_hash":"hash","quote_hash":"hash"}]
                """);
        when(meetingRepository.findVisibleToOrgAndId(ORG_ID, MEETING_ID))
                .thenReturn(Optional.of(new Meeting()));
        when(runRepository.findLatestByMeetingIdVisibleToOrg(MEETING_ID, ORG_ID))
                .thenReturn(Optional.of(run));
        when(decisionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID)).thenReturn(List.of());
        when(actionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getLatest(tenant, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(exception.getReason()).isEqualTo("ANALYSIS_RESULT_INVALID");
                });

        verifyNoInteractions(accessAuditService);
    }

    @Test
    void getLatest_semanticallyInvalidCitationRange_failsClosed() {
        MeetingAnalysisRun run = analysisRun();
        run.setSummaryCitations("""
                [{"claim":"Claim","source_index":0,"source_text":"evidence",
                  "similarity":0.9,"grounded":true,"status":"PASSED","reason":"",
                  "source_char_start":8,"source_char_end":2,
                  "source_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "quote_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]
                """);
        when(meetingRepository.findVisibleToOrgAndId(ORG_ID, MEETING_ID))
                .thenReturn(Optional.of(new Meeting()));
        when(runRepository.findLatestByMeetingIdVisibleToOrg(MEETING_ID, ORG_ID))
                .thenReturn(Optional.of(run));
        when(decisionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID)).thenReturn(List.of());
        when(actionRepository.findByAnalysisRunIdAndMeetingIdVisibleToOrg(
                RUN_ID, MEETING_ID, ORG_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getLatest(tenant, MEETING_ID))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(exception.getReason()).isEqualTo("ANALYSIS_RESULT_INVALID");
                });

        verifyNoInteractions(accessAuditService);
    }

    private static MeetingAnalysisRun analysisRun() {
        MeetingAnalysisRun run = new MeetingAnalysisRun();
        run.setAnalysisRunId(RUN_ID);
        run.setMeetingId(MEETING_ID);
        run.setTenantId(ORG_ID);
        run.setOrgId(ORG_ID);
        run.setTranscriptSessionId("SES-42");
        run.setTranscriptSha256("a".repeat(64));
        run.setAnalyzerContractVersion("5-adr0043");
        run.setModel("qwen2.5:7b");
        run.setBackend("ollama");
        run.setPromptVersion("ollama-v1");
        run.setPayloadHash("b".repeat(64));
        run.setSummary("Dogrulanmis ozet.");
        run.setSummaryGroundingStatus("verified");
        run.setSummaryCitations("""
                [{"claim":"Dogrulanmis ozet.","source_index":0,
                  "source_text":"Dogrulanmis ozet.","similarity":1.0,
                  "grounded":true,"status":"PASSED","reason":"",
                  "start_sec":0.0,"source_char_start":0,"source_char_end":19,
                  "source_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "quote_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]
                """);
        run.setCitations("[]");
        run.setRejectedClaims("""
                [{"claim":"Desteksiz","kind":"summary","status":"FAILED",
                  "reason":"ungrounded","similarity":0.1}]
                """);
        run.setUngroundedCount(1);
        run.setRedacted(true);
        run.setRedactionCount(2);
        run.setGeneratedAt(Instant.parse("2026-07-11T20:00:00Z"));
        return run;
    }
}
