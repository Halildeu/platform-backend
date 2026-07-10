package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.meeting.dto.v1.admin.MeetingIntelligenceActionItem;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceCitation;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceRejectedClaim;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestionRequest;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestionResponse;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingAnalysisCitation;
import com.example.meeting.model.MeetingAnalysisOutboxEvent;
import com.example.meeting.model.MeetingAnalysisRejectedClaim;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingAnalysisRunStatus;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAnalysisCitationRepository;
import com.example.meeting.repository.MeetingAnalysisOutboxEventRepository;
import com.example.meeting.repository.MeetingAnalysisRejectedClaimRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.repository.MeetingSummaryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * #244 BE-1 acceptance conditions, verified at the unit level (mocked
 * repositories — no DB): idempotency (exact-key replay + conflicting-payload
 * rejection), staleness protection, single-canonical-per-meeting supersession,
 * and the action.assigned attribution guard.
 */
@ExtendWith(MockitoExtension.class)
class MeetingAnalysisIngestionServiceTest {

    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private MeetingRepository meetingRepository;
    @Mock
    private MeetingAnalysisRunRepository analysisRunRepository;
    @Mock
    private MeetingSummaryRepository summaryRepository;
    @Mock
    private MeetingDecisionRepository decisionRepository;
    @Mock
    private MeetingActionRepository actionRepository;
    @Mock
    private MeetingAnalysisCitationRepository citationRepository;
    @Mock
    private MeetingAnalysisRejectedClaimRepository rejectedClaimRepository;
    @Mock
    private MeetingAnalysisOutboxEventRepository outboxEventRepository;

    private MeetingAnalysisIngestionService service;

    @BeforeEach
    void setUp() {
        service = new MeetingAnalysisIngestionService(
                meetingRepository,
                analysisRunRepository,
                summaryRepository,
                decisionRepository,
                actionRepository,
                citationRepository,
                rejectedClaimRepository,
                outboxEventRepository,
                new ObjectMapper());
    }

    private static Meeting meeting() {
        Meeting meeting = new Meeting();
        meeting.setTenantId(TENANT_ID);
        return meeting;
    }

    private static MeetingAnalysisResultIngestionRequest request(String runId, String revision) {
        return new MeetingAnalysisResultIngestionRequest(
                MEETING_ID,
                runId,
                "transcript-1",
                revision,
                "1.0",
                "gpt-mock",
                "prompt-v1",
                "Ozet metni.",
                List.of("Karar 1"),
                List.of(new MeetingIntelligenceActionItem("Aksiyon 1", "halil@example.com", null)),
                List.of(),
                List.of(),
                Instant.parse("2026-07-10T10:00:00Z"));
    }

    @BeforeEach
    void stubRepositorySaves() {
        lenient().when(analysisRunRepository.save(any(MeetingAnalysisRun.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void ingest_freshRun_persistsSummaryDecisionsActionsAndEmitsBothOutboxEvents() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting()));
        when(analysisRunRepository.findByAnalysisRunId("run-1")).thenReturn(Optional.empty());
        when(analysisRunRepository.findByMeetingIdAndTranscriptRevisionAndAnalyzerContractVersion(
                MEETING_ID, "rev-1", "1.0")).thenReturn(Optional.empty());
        when(analysisRunRepository.findByMeetingIdAndStatus(MEETING_ID, MeetingAnalysisRunStatus.CANONICAL))
                .thenReturn(Optional.empty());

        MeetingAnalysisResultIngestionResponse response = service.ingest(MEETING_ID, request("run-1", "rev-1"));

        assertThat(response.replayed()).isFalse();
        assertThat(response.analysisRunId()).isEqualTo("run-1");
        verify(summaryRepository).save(any());
        verify(decisionRepository).save(any(MeetingDecision.class));
        verify(actionRepository).save(any(MeetingAction.class));

        ArgumentCaptor<MeetingAnalysisOutboxEvent> outboxCaptor =
                ArgumentCaptor.forClass(MeetingAnalysisOutboxEvent.class);
        verify(outboxEventRepository, times(2)).save(outboxCaptor.capture());
        List<String> emittedTypes = outboxCaptor.getAllValues().stream()
                .map(MeetingAnalysisOutboxEvent::getEventType)
                .toList();
        assertThat(emittedTypes).containsExactlyInAnyOrder("summary.ready", "action.assigned");
    }

    @Test
    void ingest_actionWithoutOwner_persistsButDoesNotEmitActionAssigned() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting()));
        when(analysisRunRepository.findByAnalysisRunId("run-1")).thenReturn(Optional.empty());
        when(analysisRunRepository.findByMeetingIdAndTranscriptRevisionAndAnalyzerContractVersion(
                MEETING_ID, "rev-1", "1.0")).thenReturn(Optional.empty());
        when(analysisRunRepository.findByMeetingIdAndStatus(MEETING_ID, MeetingAnalysisRunStatus.CANONICAL))
                .thenReturn(Optional.empty());

        MeetingAnalysisResultIngestionRequest request = new MeetingAnalysisResultIngestionRequest(
                MEETING_ID, "run-1", "transcript-1", "rev-1", "1.0", null, null,
                "Ozet.", List.of(),
                List.of(new MeetingIntelligenceActionItem("Sahipsiz aksiyon", null, null)),
                List.of(), List.of(), Instant.parse("2026-07-10T10:00:00Z"));

        service.ingest(MEETING_ID, request);

        ArgumentCaptor<MeetingAction> actionCaptor = ArgumentCaptor.forClass(MeetingAction.class);
        verify(actionRepository).save(actionCaptor.capture());
        assertThat(actionCaptor.getValue().getAssigneeSubject()).isNull();

        ArgumentCaptor<MeetingAnalysisOutboxEvent> outboxCaptor =
                ArgumentCaptor.forClass(MeetingAnalysisOutboxEvent.class);
        verify(outboxEventRepository, times(1)).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("summary.ready");
    }

    @Test
    void ingest_sameAnalysisRunIdSamePayload_isIdempotentReplay() {
        MeetingAnalysisResultIngestionRequest request = request("run-1", "rev-1");
        MeetingAnalysisRun existing = existingRun("run-1", service_hashFor(request));

        when(analysisRunRepository.findByAnalysisRunId("run-1")).thenReturn(Optional.of(existing));

        MeetingAnalysisResultIngestionResponse response = service.ingest(MEETING_ID, request);

        assertThat(response.replayed()).isTrue();
        assertThat(response.analysisRunId()).isEqualTo("run-1");
        verify(meetingRepository, never()).findById(any());
        verify(summaryRepository, never()).save(any());
    }

    @Test
    void ingest_sameAnalysisRunIdDifferentPayload_isConflict() {
        MeetingAnalysisRun existing = existingRun("run-1", "deliberately-wrong-hash");
        when(analysisRunRepository.findByAnalysisRunId("run-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.ingest(MEETING_ID, request("run-1", "rev-1")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
    }

    @Test
    void ingest_sameLogicalIdentityDifferentRunId_replaysUnderOriginalRunId() {
        MeetingAnalysisResultIngestionRequest incoming = request("run-2", "rev-1");
        MeetingAnalysisRun existing = existingRun("run-1", service_hashFor(request("run-1", "rev-1")));

        when(analysisRunRepository.findByAnalysisRunId("run-2")).thenReturn(Optional.empty());
        when(analysisRunRepository.findByMeetingIdAndTranscriptRevisionAndAnalyzerContractVersion(
                MEETING_ID, "rev-1", "1.0")).thenReturn(Optional.of(existing));

        MeetingAnalysisResultIngestionResponse response = service.ingest(MEETING_ID, incoming);

        assertThat(response.replayed()).isTrue();
        assertThat(response.analysisRunId()).isEqualTo("run-1");
    }

    @Test
    void ingest_olderTranscriptRevisionThanCanonical_isRejectedAsStale() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting()));
        when(analysisRunRepository.findByAnalysisRunId("run-2")).thenReturn(Optional.empty());
        when(analysisRunRepository.findByMeetingIdAndTranscriptRevisionAndAnalyzerContractVersion(
                MEETING_ID, "rev-2", "1.0")).thenReturn(Optional.empty());

        MeetingAnalysisRun currentCanonical = existingRun("run-1", "hash-1");
        currentCanonical.setGeneratedAt(Instant.parse("2026-07-10T12:00:00Z"));
        when(analysisRunRepository.findByMeetingIdAndStatus(MEETING_ID, MeetingAnalysisRunStatus.CANONICAL))
                .thenReturn(Optional.of(currentCanonical));

        MeetingAnalysisResultIngestionRequest staleRequest = new MeetingAnalysisResultIngestionRequest(
                MEETING_ID, "run-2", "transcript-1", "rev-2", "1.0", null, null,
                "Eski analiz.", List.of(), List.of(), List.of(), List.of(),
                Instant.parse("2026-07-10T09:00:00Z")); // older than canonical's generatedAt

        assertThatThrownBy(() -> service.ingest(MEETING_ID, staleRequest))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getReason()).isEqualTo("STALE_TRANSCRIPT_ANALYSIS");
                });
        verify(summaryRepository, never()).save(any());
    }

    @Test
    void ingest_newerTranscriptRevisionThanCanonical_supersedesPreviousRun() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting()));
        when(analysisRunRepository.findByAnalysisRunId("run-2")).thenReturn(Optional.empty());
        when(analysisRunRepository.findByMeetingIdAndTranscriptRevisionAndAnalyzerContractVersion(
                MEETING_ID, "rev-2", "1.0")).thenReturn(Optional.empty());

        MeetingAnalysisRun currentCanonical = existingRun("run-1", "hash-1");
        currentCanonical.setGeneratedAt(Instant.parse("2026-07-10T09:00:00Z"));
        currentCanonical.setStatus(MeetingAnalysisRunStatus.CANONICAL);
        when(analysisRunRepository.findByMeetingIdAndStatus(MEETING_ID, MeetingAnalysisRunStatus.CANONICAL))
                .thenReturn(Optional.of(currentCanonical));

        MeetingAnalysisResultIngestionRequest newerRequest = new MeetingAnalysisResultIngestionRequest(
                MEETING_ID, "run-2", "transcript-1", "rev-2", "1.0", null, null,
                "Yeni analiz.", List.of(), List.of(), List.of(), List.of(),
                Instant.parse("2026-07-10T12:00:00Z")); // newer than canonical's generatedAt

        ArgumentCaptor<MeetingAnalysisRun> savedRunCaptor = ArgumentCaptor.forClass(MeetingAnalysisRun.class);
        MeetingAnalysisResultIngestionResponse response = service.ingest(MEETING_ID, newerRequest);

        assertThat(response.replayed()).isFalse();
        assertThat(currentCanonical.getStatus()).isEqualTo(MeetingAnalysisRunStatus.SUPERSEDED);
        assertThat(currentCanonical.getSupersededByAnalysisRunId()).isEqualTo("run-2");
        verify(analysisRunRepository, times(2)).save(savedRunCaptor.capture());
        // #244 acceptance condition 2: the NEW run carries supersedesAnalysisRunId
        // (backward pointer), not just the old run's forward pointer.
        MeetingAnalysisRun newRun = savedRunCaptor.getAllValues().stream()
                .filter(r -> "run-2".equals(r.getAnalysisRunId()))
                .findFirst()
                .orElseThrow();
        assertThat(newRun.getSupersedesAnalysisRunId()).isEqualTo("run-1");
    }

    @Test
    void ingest_persistsCitationsAndRejectedClaims() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.of(meeting()));
        when(analysisRunRepository.findByAnalysisRunId("run-1")).thenReturn(Optional.empty());
        when(analysisRunRepository.findByMeetingIdAndTranscriptRevisionAndAnalyzerContractVersion(
                MEETING_ID, "rev-1", "1.0")).thenReturn(Optional.empty());
        when(analysisRunRepository.findByMeetingIdAndStatus(MEETING_ID, MeetingAnalysisRunStatus.CANONICAL))
                .thenReturn(Optional.empty());

        MeetingIntelligenceCitation citation = new MeetingIntelligenceCitation(
                "Toplanti 5 dakika erken bitti.", 0, "kaynak metin", 0.92, true,
                "grounded", null, 12.5, 0, 30, "sha256:src", "sha256:quote");
        MeetingIntelligenceRejectedClaim rejectedClaim = new MeetingIntelligenceRejectedClaim(
                "Butce onaylandi.", "decision", "ungrounded", "no matching source", 0.31);

        MeetingAnalysisResultIngestionRequest request = new MeetingAnalysisResultIngestionRequest(
                MEETING_ID, "run-1", "transcript-1", "rev-1", "1.0", null, null,
                "Ozet.", List.of(), List.of(), List.of(citation), List.of(rejectedClaim),
                Instant.parse("2026-07-10T10:00:00Z"));

        service.ingest(MEETING_ID, request);

        ArgumentCaptor<MeetingAnalysisCitation> citationCaptor =
                ArgumentCaptor.forClass(MeetingAnalysisCitation.class);
        verify(citationRepository).save(citationCaptor.capture());
        assertThat(citationCaptor.getValue().getClaim()).isEqualTo("Toplanti 5 dakika erken bitti.");
        assertThat(citationCaptor.getValue().getGrounded()).isTrue();

        ArgumentCaptor<MeetingAnalysisRejectedClaim> rejectedCaptor =
                ArgumentCaptor.forClass(MeetingAnalysisRejectedClaim.class);
        verify(rejectedClaimRepository).save(rejectedCaptor.capture());
        assertThat(rejectedCaptor.getValue().getClaim()).isEqualTo("Butce onaylandi.");
        assertThat(rejectedCaptor.getValue().getReason()).isEqualTo("no matching source");
    }

    @Test
    void ingest_meetingIdMismatchBetweenPathAndBody_isRejectedBeforeLookup() {
        MeetingAnalysisResultIngestionRequest mismatched = new MeetingAnalysisResultIngestionRequest(
                UUID.randomUUID(), "run-1", "transcript-1", "rev-1", "1.0", null, null,
                "Ozet.", List.of(), List.of(), List.of(), List.of(), Instant.now());

        assertThatThrownBy(() -> service.ingest(MEETING_ID, mismatched))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(meetingRepository, never()).findById(any());
    }

    @Test
    void ingest_unknownMeeting_returns404() {
        when(meetingRepository.findById(MEETING_ID)).thenReturn(Optional.empty());
        when(analysisRunRepository.findByAnalysisRunId("run-1")).thenReturn(Optional.empty());
        when(analysisRunRepository.findByMeetingIdAndTranscriptRevisionAndAnalyzerContractVersion(
                MEETING_ID, "rev-1", "1.0")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest(MEETING_ID, request("run-1", "rev-1")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    // -- helpers ------------------------------------------------------------

    private static MeetingAnalysisRun existingRun(String runId, String payloadHash) {
        MeetingAnalysisRun run = new MeetingAnalysisRun();
        run.setAnalysisRunId(runId);
        run.setPayloadHash(payloadHash);
        run.setGeneratedAt(Instant.parse("2026-07-10T10:00:00Z"));
        return run;
    }

    /** Recomputes the same hash the service would, for replay-match fixtures. */
    private static String service_hashFor(MeetingAnalysisResultIngestionRequest request) {
        MeetingAnalysisIngestionService probe = new MeetingAnalysisIngestionService(
                null, null, null, null, null, null, null, null, new ObjectMapper());
        return probe.hashPayload(request);
    }
}
