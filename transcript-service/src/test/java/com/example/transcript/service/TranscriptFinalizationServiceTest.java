package com.example.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.transcript.model.TranscriptEventOutbox;
import com.example.transcript.finalization.FinalizedTranscriptSnapshotCodec;
import com.example.transcript.finalization.TranscriptSnapshotHasher;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptFinalizationState;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.security.AdminTenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class TranscriptFinalizationServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-17T13:00:00.123456789Z");

    private final TranscriptSessionAssociationRepository associations =
            mock(TranscriptSessionAssociationRepository.class);
    private final TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
    private final TranscriptFinalizationRepository finalizations =
            mock(TranscriptFinalizationRepository.class);
    private final TranscriptEventOutboxRepository outbox = mock(TranscriptEventOutboxRepository.class);
    private final TranscriptSessionAssociation association = mock(TranscriptSessionAssociation.class);
    private final SessionErasureFence erasureFence = mock(SessionErasureFence.class);
    private final AtomicLong currentVersion = new AtomicLong();
    private final AtomicLong currentCycleVersion = new AtomicLong();
    private final AtomicReference<TranscriptFinalizationState> currentState =
            new AtomicReference<>(TranscriptFinalizationState.AWAITING_FINISH);
    private final AtomicReference<TranscriptFinalization> storedFinalization = new AtomicReference<>();
    private TranscriptFinalizationService service;

    @BeforeEach
    void setUp() {
        when(associations.findCanonicalForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(Optional.of(association));
        when(association.getFinalizationVersion()).thenAnswer(ignored -> currentVersion.get());
        when(association.getFinalizationCycleVersion()).thenAnswer(ignored -> currentCycleVersion.get());
        when(association.getFinalizationState()).thenAnswer(ignored -> currentState.get());
        doAnswer(invocation -> {
            currentVersion.set(invocation.getArgument(0));
            return null;
        }).when(association).setFinalizationVersion(any(Long.class));
        doAnswer(invocation -> {
            currentCycleVersion.set(invocation.getArgument(0));
            return null;
        }).when(association).setFinalizationCycleVersion(any(Long.class));
        doAnswer(invocation -> {
            currentState.set(invocation.getArgument(0));
            return null;
        }).when(association).setFinalizationState(any());
        when(finalizations.save(any())).thenAnswer(invocation -> {
            TranscriptFinalization row = invocation.getArgument(0);
            storedFinalization.set(row);
            return row;
        });
        when(finalizations.findByTenantIdAndMeetingIdAndSessionIdAndFinalizationVersion(
                TENANT, MEETING, SESSION, 1L))
                .thenAnswer(ignored -> Optional.ofNullable(storedFinalization.get()));
        when(segments.findCanonicalSessionForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(List.of(finalSegment("approved transcript")));
        service = new TranscriptFinalizationService(
                associations, segments, finalizations, outbox,
                new FinalizedTranscriptSnapshotCodec(
                        new TranscriptSnapshotHasher(), new ObjectMapper()),
                erasureFence,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void duplicateOccurrenceCreatesOneThinOutboxEffect() throws Exception {
        ArgumentCaptor<TranscriptEventOutbox> event =
                ArgumentCaptor.forClass(TranscriptEventOutbox.class);

        var created = service.finalizeTranscript(context(), MEETING, SESSION, 1L);
        var replayed = service.finalizeTranscript(context(), MEETING, SESSION, 1L);

        assertThat(replayed).isEqualTo(created);
        assertThat(created.finalizedAt())
                .isEqualTo(Instant.parse("2026-07-17T13:00:00.123456Z"));
        verify(outbox, times(1)).save(event.capture());
        assertThat(event.getValue().getEventKey())
                .isEqualTo("meeting.transcript|" + SESSION + "|meeting.transcript.ready|1");
        JsonNode payload = new ObjectMapper().readTree(event.getValue().getPayload());
        List<String> fieldNames = new ArrayList<>();
        payload.fieldNames().forEachRemaining(fieldNames::add);
        assertThat(fieldNames).containsExactly(
                "schema", "eventType", "analysisRunId", "meetingId", "tenantId", "orgId",
                "generatedAt", "transcriptSessionId", "finalizationVersion", "segmentCount");
        assertThat(payload.path("schema").asText()).isEqualTo("meeting.event.v1");
        assertThat(payload.path("eventType").asText()).isEqualTo("meeting.transcript.ready");
        assertThat(UUID.fromString(payload.path("analysisRunId").asText()))
                .isEqualTo(storedFinalization.get().getAnalysisRunId());
        assertThat(payload.path("meetingId").asText()).isEqualTo(MEETING.toString());
        assertThat(payload.path("tenantId").asText()).isEqualTo(TENANT.toString());
        assertThat(payload.path("orgId").asText()).isEqualTo(TENANT.toString());
        assertThat(payload.path("transcriptSessionId").asText()).isEqualTo(SESSION.toString());
        assertThat(payload.path("finalizationVersion").asLong()).isEqualTo(1L);
        assertThat(payload.path("segmentCount").asInt()).isEqualTo(1);
        payload.fields().forEachRemaining(field -> assertThat(field.getValue().isValueNode()).isTrue());
        verify(association).setFinalizationCycleVersion(1L);
        verify(association).setFinalizationState(TranscriptFinalizationState.FINALIZED);
        verify(association).setQuiescenceDueAt(null);
        assertThat(storedFinalization.get().getCanonicalTranscript())
                .isEqualTo("approved transcript");
        assertThat(storedFinalization.get().getCanonicalTranscriptSha256())
                .matches("[0-9a-f]{64}");
        assertThat(storedFinalization.get().getCanonicalSegments())
                .contains("approved transcript");
        assertThat(storedFinalization.get().getCanonicalProjectionSha256())
                .matches("[0-9a-f]{64}");
        assertThat(storedFinalization.get().getAnalysisRunId()).isNotNull();
    }

    @Test
    void draftSegmentCannotProduceReadyEvent() {
        TranscriptSegment draft = finalSegment(null);
        draft.setStatus(TranscriptSegmentStatus.DRAFT);
        draft.setTextDraft("private draft");
        when(segments.findCanonicalSessionForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(List.of(draft));

        assertThatThrownBy(() -> service.finalizeTranscript(context(), MEETING, SESSION, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409));
        verify(outbox, never()).save(any());
    }

    @Test
    void sameVersionRejectsAChangedSnapshot() {
        service.finalizeTranscript(context(), MEETING, SESSION, 1L);
        when(segments.findCanonicalSessionForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(List.of(finalSegment("changed transcript")));

        assertThatThrownBy(() -> service.finalizeTranscript(context(), MEETING, SESSION, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(409));
        verify(outbox, times(1)).save(any());
    }

    @Test
    void replayOfAnOlderOccurrenceDoesNotRetireANewerQuiescenceCycle() {
        service.finalizeTranscript(context(), MEETING, SESSION, 1L);
        clearInvocations(association);
        when(association.getFinalizationCycleVersion()).thenReturn(2L);
        when(association.getFinalizationState()).thenReturn(TranscriptFinalizationState.QUIESCING);

        service.finalizeTranscript(context(), MEETING, SESSION, 1L);

        verify(association, never()).setFinalizationState(any());
        verify(association, never()).setFinalizationCycleVersion(any(Long.class));
    }

    @Test
    void foreignTenantCannotResolveCanonicalAssociation() {
        UUID foreign = UUID.randomUUID();
        when(associations.findCanonicalForUpdate(foreign, MEETING, SESSION))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.finalizeTranscript(
                new AdminTenantContext(foreign, "foreign"), MEETING, SESSION, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));
        verify(outbox, never()).save(any());
    }

    private TranscriptSegment finalSegment(String text) {
        TranscriptSegment row = new TranscriptSegment();
        row.setId(UUID.randomUUID());
        row.setTenantId(TENANT);
        row.setOrgId(TENANT);
        row.setMeetingId(MEETING);
        row.setSessionId(SESSION);
        row.setStartTime(0.0d);
        row.setEndTime(1.0d);
        row.setTextFinal(text);
        row.setStatus(TranscriptSegmentStatus.FINALIZED);
        return row;
    }

    private AdminTenantContext context() {
        return new AdminTenantContext(TENANT, "admin");
    }
}
