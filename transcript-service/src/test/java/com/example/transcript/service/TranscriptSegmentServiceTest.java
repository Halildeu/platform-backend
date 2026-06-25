package com.example.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.transcript.dto.CreateTranscriptSegmentRequest;
import com.example.transcript.dto.TranscriptSegmentDto;
import com.example.transcript.dto.UpdateTranscriptSegmentRequest;
import com.example.transcript.directstt.DirectSttTranscriptResultEvent;
import com.example.transcript.model.TranscriptAccessType;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.security.AdminTenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service-level unit tests focused on the KVKK m.12 audit-per-access contract:
 * every read/list/search/export MUST write exactly one access-audit row of the
 * right type, the search TERM is never forwarded to the audit writer, and the
 * optimistic-lock precondition surfaces a 409.
 *
 * <p>Mockito-only (no Spring); the {@link TranscriptAccessAuditService} is a
 * mock so we can assert the exact audit calls. The transcript-free invariant of
 * the AUDIT TABLE itself is proved structurally in {@code TranscriptSegmentTest}
 * + the Postgres integration test; here we prove the SERVICE never even passes
 * text/term to the audit layer (the audit-writer signatures have no such param).
 */
@ExtendWith(MockitoExtension.class)
class TranscriptSegmentServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SESSION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SEGMENT = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock
    private TranscriptSegmentRepository repository;
    @Mock
    private TranscriptAccessAuditService accessAuditService;

    private TranscriptSegmentService service;

    private final AdminTenantContext context = new AdminTenantContext(TENANT, "admin@example.com");

    @BeforeEach
    void setUp() {
        service = new TranscriptSegmentService(repository, accessAuditService, 50, 200, 50000);
    }

    // ─────────────────────────── READ audits ──────────────────────────

    @Test
    void getSegment_writesReadAudit_withSegmentMeetingSession() {
        TranscriptSegment seg = segment();
        when(repository.findVisibleToOrgAndId(TENANT, SEGMENT)).thenReturn(Optional.of(seg));

        TranscriptSegmentDto dto = service.getSegment(context, SEGMENT);

        assertThat(dto.id()).isEqualTo(SEGMENT);
        verify(accessAuditService).recordRead(context, SEGMENT, MEETING, SESSION);
    }

    @Test
    void getSegment_notFound_doesNotWriteAudit() {
        when(repository.findVisibleToOrgAndId(TENANT, SEGMENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSegment(context, SEGMENT))
                .isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(accessAuditService);
    }

    // ─────────────────────────── LIST audits ──────────────────────────

    @Test
    void listByMeeting_writesListAudit_withReturnedCount() {
        Page<TranscriptSegment> page = new PageImpl<>(List.of(segment(), segment()),
                PageRequest.of(0, 50), 2);
        when(repository.findVisibleToOrgByMeeting(eq(TENANT), eq(MEETING), any())).thenReturn(page);

        service.listByMeeting(context, MEETING, 0, 50);

        verify(accessAuditService).recordList(context, MEETING, null, 2);
    }

    @Test
    void listBySession_writesListAudit_withReturnedCount() {
        Page<TranscriptSegment> page = new PageImpl<>(List.of(segment()),
                PageRequest.of(0, 50), 1);
        when(repository.findVisibleToOrgBySession(eq(TENANT), eq(SESSION), any())).thenReturn(page);

        service.listBySession(context, SESSION, 0, 50);

        verify(accessAuditService).recordList(context, null, SESSION, 1);
    }

    // ────────────────────────── SEARCH audits ─────────────────────────

    @Test
    void search_writesSearchAudit_withCountOnly_andNeverForwardsTerm() {
        Page<TranscriptSegment> page = new PageImpl<>(List.of(segment()),
                PageRequest.of(0, 50), 1);
        when(repository.searchVisibleToOrg(eq(TENANT), eq(MEETING), eq("secret budget"), any()))
                .thenReturn(page);

        service.search(context, "Secret Budget", MEETING, 0, 50);

        // The audit writer is called with (context, meetingId, count) only —
        // there is structurally NO parameter for the search term.
        verify(accessAuditService).recordSearch(context, MEETING, 1);
    }

    @Test
    void search_blankQuery_isRejected_andNoAudit() {
        assertThatThrownBy(() -> service.search(context, "   ", MEETING, 0, 50))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(accessAuditService);
    }

    // ────────────────────────── EXPORT audits ─────────────────────────

    @Test
    void exportByMeeting_writesExportAudit_withExportedCount() {
        when(repository.findAllVisibleToOrgByMeeting(eq(TENANT), eq(MEETING), any()))
                .thenReturn(List.of(segment(), segment(), segment()));

        List<TranscriptSegmentDto> result = service.exportByMeeting(context, MEETING);

        assertThat(result).hasSize(3);
        verify(accessAuditService).recordExport(context, MEETING, 3);
    }

    @Test
    void exportByMeeting_overCap_isRejected_andNoAudit() {
        // exportMaxRows=2 here so 3 returned (cap+1) trips the guard.
        service = new TranscriptSegmentService(repository, accessAuditService, 50, 200, 2);
        when(repository.findAllVisibleToOrgByMeeting(eq(TENANT), eq(MEETING), any()))
                .thenReturn(List.of(segment(), segment(), segment()));

        assertThatThrownBy(() -> service.exportByMeeting(context, MEETING))
                .isInstanceOf(ResponseStatusException.class);
        verify(accessAuditService, never()).recordExport(any(), any(), anyInt());
    }

    // ────────────────────── CREATE (no read audit) ────────────────────

    @Test
    void create_setsBothTenantAndOrgId_andWritesNoAccessAudit() {
        ArgumentCaptor<TranscriptSegment> captor = ArgumentCaptor.forClass(TranscriptSegment.class);
        when(repository.saveAndFlush(captor.capture())).thenAnswer(inv -> {
            TranscriptSegment s = inv.getArgument(0);
            s.setId(SEGMENT);
            return s;
        });

        CreateTranscriptSegmentRequest req = new CreateTranscriptSegmentRequest(
                MEETING, SESSION, null, 0.0, 1.5, "draft", null, 0.9, null);
        service.create(context, req);

        TranscriptSegment saved = captor.getValue();
        // Canonical org_id write: BOTH columns set to the tenant UUID.
        assertThat(saved.getTenantId()).isEqualTo(TENANT);
        assertThat(saved.getOrgId()).isEqualTo(TENANT);
        assertThat(saved.getStatus()).isEqualTo(TranscriptSegmentStatus.DRAFT);
        // create is a write, not a read of personal data → no access audit.
        verifyNoInteractions(accessAuditService);
    }

    @Test
    void create_endBeforeStart_isRejected() {
        CreateTranscriptSegmentRequest req = new CreateTranscriptSegmentRequest(
                MEETING, SESSION, null, 5.0, 1.0, "draft", null, 0.9, null);
        assertThatThrownBy(() -> service.create(context, req))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ───────────────── DIRECT-STT stream upsert ──────────────────────

    @Test
    void upsertDirectSttDraft_createsSourceScopedDraft_withoutAccessAudit() {
        DirectSttTranscriptResultEvent event = directSttEvent("1-0", "merhaba dunya", 1.2d);
        when(repository.findDirectSttSourceChunk(event.tenantId(), "SES-abc", 5L))
                .thenReturn(Optional.empty());
        ArgumentCaptor<TranscriptSegment> captor = ArgumentCaptor.forClass(TranscriptSegment.class);
        when(repository.saveAndFlush(captor.capture())).thenAnswer(inv -> {
            TranscriptSegment s = inv.getArgument(0);
            s.setId(SEGMENT);
            return s;
        });

        TranscriptSegmentDto dto = service.upsertDirectSttDraft(event);

        assertThat(dto.id()).isEqualTo(SEGMENT);
        TranscriptSegment saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(event.tenantId());
        assertThat(saved.getOrgId()).isEqualTo(event.tenantId());
        assertThat(saved.getMeetingId()).isEqualTo(MEETING);
        assertThat(saved.getSessionId()).isNull();
        assertThat(saved.getStartTime()).isEqualTo(1.25d);
        assertThat(saved.getEndTime()).isEqualTo(2.45d);
        assertThat(saved.getTextDraft()).isEqualTo("merhaba dunya");
        assertThat(saved.getStatus()).isEqualTo(TranscriptSegmentStatus.DRAFT);
        assertThat(saved.getSourceSystem()).isEqualTo("DIRECT_STT");
        assertThat(saved.getSourceEventId()).isEqualTo("1-0");
        assertThat(saved.getSourceSessionId()).isEqualTo("SES-abc");
        assertThat(saved.getSourceChunkSeq()).isEqualTo(5L);
        assertThat(saved.getSourceSha256()).isEqualTo("deadbeefcafe0000sha");
        assertThat(saved.getSourceCorrelationId()).isEqualTo("corr-direct-stt");
        verifyNoInteractions(accessAuditService);
    }

    @Test
    void upsertDirectSttDraft_replayUpdatesSameSourceChunkWithoutDuplicateCreate() {
        DirectSttTranscriptResultEvent event = directSttEvent("2-0", "ikinci taslak", 2.0d);
        TranscriptSegment existing = segment();
        existing.setSourceSystem("DIRECT_STT");
        existing.setSourceSessionId("SES-abc");
        existing.setSourceChunkSeq(5L);
        when(repository.findDirectSttSourceChunk(event.tenantId(), "SES-abc", 5L))
                .thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        service.upsertDirectSttDraft(event);

        assertThat(existing.getTextDraft()).isEqualTo("ikinci taslak");
        assertThat(existing.getStartTime()).isEqualTo(1.25d);
        assertThat(existing.getEndTime()).isEqualTo(3.25d);
        assertThat(existing.getSourceEventId()).isEqualTo("2-0");
        verify(repository).saveAndFlush(existing);
        verifyNoInteractions(accessAuditService);
    }

    @Test
    void upsertDirectSttDraft_doesNotOverwriteFinalizedTextOnLateReplay() {
        DirectSttTranscriptResultEvent event = directSttEvent("3-0", "late machine replay", 1.0d);
        TranscriptSegment existing = segment();
        existing.setSourceSystem("DIRECT_STT");
        existing.setSourceSessionId("SES-abc");
        existing.setSourceChunkSeq(5L);
        existing.setTextFinal("human final text");
        existing.setStatus(TranscriptSegmentStatus.FINALIZED);
        when(repository.findDirectSttSourceChunk(event.tenantId(), "SES-abc", 5L))
                .thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(existing)).thenReturn(existing);

        service.upsertDirectSttDraft(event);

        assertThat(existing.getStartTime()).isEqualTo(0.0d);
        assertThat(existing.getEndTime()).isEqualTo(1.5d);
        assertThat(existing.getTextDraft()).isEqualTo("hello world");
        assertThat(existing.getTextFinal()).isEqualTo("human final text");
        assertThat(existing.getStatus()).isEqualTo(TranscriptSegmentStatus.FINALIZED);
        assertThat(existing.getSourceEventId()).isEqualTo("3-0");
    }

    // ──────────────────── UPDATE optimistic-lock 409 ──────────────────

    @Test
    void update_versionMismatch_throwsOptimisticLockingFailure() {
        TranscriptSegment seg = segment();
        seg.setVersion(7L);
        when(repository.findVisibleToOrgAndId(TENANT, SEGMENT)).thenReturn(Optional.of(seg));

        UpdateTranscriptSegmentRequest req = new UpdateTranscriptSegmentRequest(
                null, null, "fixed", null, null, null, null, 3L); // expected 3 != actual 7

        assertThatThrownBy(() -> service.update(context, SEGMENT, req))
                .isInstanceOf(OptimisticLockingFailureException.class);
        // mutation must NOT be persisted on a precondition failure.
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void update_matchingVersion_appliesAndPersists() {
        TranscriptSegment seg = segment();
        seg.setVersion(7L);
        when(repository.findVisibleToOrgAndId(TENANT, SEGMENT)).thenReturn(Optional.of(seg));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateTranscriptSegmentRequest req = new UpdateTranscriptSegmentRequest(
                null, null, null, "final text", null, TranscriptSegmentStatus.FINALIZED, null, 7L);

        TranscriptSegmentDto dto = service.update(context, SEGMENT, req);

        assertThat(dto.textFinal()).isEqualTo("final text");
        assertThat(dto.status()).isEqualTo(TranscriptSegmentStatus.FINALIZED);
        verify(repository, times(1)).saveAndFlush(seg);
    }

    @Test
    void update_nullExpectedVersion_skipsPreconditionButStillPersists() {
        TranscriptSegment seg = segment();
        seg.setVersion(2L);
        when(repository.findVisibleToOrgAndId(TENANT, SEGMENT)).thenReturn(Optional.of(seg));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateTranscriptSegmentRequest req = new UpdateTranscriptSegmentRequest(
                null, null, "patched", null, null, null, null, null);

        service.update(context, SEGMENT, req);
        verify(repository).saveAndFlush(seg);
    }

    // ─────────────────────────── fixtures ─────────────────────────────

    private static TranscriptSegment segment() {
        TranscriptSegment seg = new TranscriptSegment();
        seg.setId(SEGMENT);
        seg.setTenantId(TENANT);
        seg.setOrgId(TENANT);
        seg.setMeetingId(MEETING);
        seg.setSessionId(SESSION);
        seg.setStartTime(0.0);
        seg.setEndTime(1.5);
        seg.setTextDraft("hello world");
        seg.setStatus(TranscriptSegmentStatus.DRAFT);
        return seg;
    }

    private static DirectSttTranscriptResultEvent directSttEvent(
            String entryId, String textDraft, Double durationSeconds) {
        UUID tenant = UUID.nameUUIDFromBytes("company:42".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new DirectSttTranscriptResultEvent(
                entryId,
                tenant,
                "42",
                "7",
                MEETING,
                "SES-abc",
                5L,
                1_250L,
                "corr-direct-stt",
                "deadbeefcafe0000sha",
                textDraft,
                durationSeconds);
    }
}
