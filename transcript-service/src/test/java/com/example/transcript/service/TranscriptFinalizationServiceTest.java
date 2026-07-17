package com.example.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transcript.model.TranscriptEventOutbox;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.security.AdminTenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private final AtomicLong currentVersion = new AtomicLong();
    private final AtomicReference<TranscriptFinalization> storedFinalization = new AtomicReference<>();
    private TranscriptFinalizationService service;

    @BeforeEach
    void setUp() {
        when(associations.findCanonicalForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(Optional.of(association));
        when(association.getFinalizationVersion()).thenAnswer(ignored -> currentVersion.get());
        doAnswer(invocation -> {
            currentVersion.set(invocation.getArgument(0));
            return null;
        }).when(association).setFinalizationVersion(any(Long.class));
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
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void duplicateOccurrenceCreatesOneThinOutboxEffect() {
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
        assertThat(event.getValue().getPayload())
                .contains("\"eventType\":\"meeting.transcript.ready\"")
                .contains("\"transcriptSessionId\":\"" + SESSION + "\"")
                .contains("\"finalizationVersion\":1")
                .contains("\"segmentCount\":1")
                .doesNotContain("approved transcript", "textDraft", "textFinal", "audio");
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
