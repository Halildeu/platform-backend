package com.example.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transcript.finalization.FinalizedTranscriptSnapshotCodec;
import com.example.transcript.finalization.TranscriptSnapshotHasher;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.model.TranscriptSessionErasureStatus;
import com.example.transcript.model.TranscriptSessionErasureTombstone;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionErasureTombstoneRepository;
import com.example.transcript.security.AdminTenantContext;
import com.example.transcript.security.AnalysisJobCapabilityIssuer;
import com.example.transcript.security.AnalysisSpecVersionPolicy;
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
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CanonicalTranscriptReadServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MEETING_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Instant FINALIZED_AT = Instant.parse("2026-07-18T02:55:00Z");

    @Mock
    private TranscriptFinalizationRepository finalizationRepository;
    @Mock
    private TranscriptSegmentRepository segmentRepository;
    @Mock
    private AnalysisJobCapabilityIssuer capabilityIssuer;
    @Mock
    private TranscriptAccessAuditService accessAuditService;
    @Mock
    private TranscriptSessionErasureTombstoneRepository erasureTombstones;
    @Mock
    private SessionErasureFence erasureFence;

    private TranscriptSnapshotHasher snapshotHasher;
    private FinalizedTranscriptSnapshotCodec snapshotCodec;
    private CanonicalTranscriptReadService service;

    @BeforeEach
    void setUp() {
        snapshotHasher = new TranscriptSnapshotHasher();
        snapshotCodec = new FinalizedTranscriptSnapshotCodec(snapshotHasher, new ObjectMapper());
        service = new CanonicalTranscriptReadService(
                finalizationRepository,
                segmentRepository,
                snapshotCodec,
                capabilityIssuer,
                new AnalysisSpecVersionPolicy("meeting-intelligence-v1"),
                accessAuditService,
                erasureTombstones,
                erasureFence);
    }

    @Test
    void read_returnsExactIntegrityCheckedOccurrenceAndMetadataOnlyAudit() {
        List<TranscriptSegment> segments = List.of(
                segment("Merhaba", 0.0, 1.2),
                segment("Dünya", 1.3, 2.0));
        TranscriptFinalization finalization = finalization(segments, false, true);
        when(finalizationRepository.findVisibleAnalysisOccurrence(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, RUN_ID))
                .thenReturn(Optional.of(finalization));

        var result = service.read(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, TENANT_ID, RUN_ID,
                "meeting-intelligence-v1", "meeting-ai");

        assertThat(result.tenantId()).isEqualTo(TENANT_ID);
        assertThat(result.meetingId()).isEqualTo(MEETING_ID);
        assertThat(result.sessionId()).isEqualTo(SESSION_ID);
        assertThat(result.finalizationVersion()).isEqualTo(3L);
        assertThat(result.finalizedAt()).isEqualTo(FINALIZED_AT);
        assertThat(result.state()).isEqualTo("FINALIZED");
        assertThat(result.transcript()).isEqualTo("Merhaba\nDünya");
        assertThat(result.transcriptSha256()).matches("^[0-9a-f]{64}$");
        assertThat(result.segmentCount()).isEqualTo(2);
        assertThat(result.segments()).extracting("text")
                .containsExactly("Merhaba", "Dünya");
        verify(capabilityIssuer, never()).issue(any());
        verify(accessAuditService).recordList(
                new AdminTenantContext(TENANT_ID, "meeting-ai", "meeting-ai"), MEETING_ID, SESSION_ID, 2);
        verify(segmentRepository, never()).findCanonicalFinalizedSession(
                TENANT_ID, MEETING_ID, SESSION_ID);
    }

    @Test
    void issueCapability_requiresPersistedProducerRunAndAllowedSpec() {
        List<TranscriptSegment> segments = List.of(segment("Merhaba", 0.0, 1.2));
        TranscriptFinalization finalization = finalization(segments, false, true);
        Instant expiresAt = Instant.parse("2026-07-18T03:10:00Z");
        when(finalizationRepository.findVisibleAnalysisOccurrence(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, RUN_ID))
                .thenReturn(Optional.of(finalization));
        when(capabilityIssuer.issue(any())).thenReturn(
                new AnalysisJobCapabilityIssuer.IssuedCapability(
                        "signed-capability", UUID.randomUUID(), expiresAt));

        var result = service.issueAnalysisCapability(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, TENANT_ID, RUN_ID,
                "meeting-intelligence-v1");

        assertThat(result.token()).isEqualTo("signed-capability");
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
        ArgumentCaptor<AnalysisJobCapabilityIssuer.JobBinding> binding =
                ArgumentCaptor.forClass(AnalysisJobCapabilityIssuer.JobBinding.class);
        verify(capabilityIssuer).issue(binding.capture());
        assertThat(binding.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(binding.getValue().meetingId()).isEqualTo(MEETING_ID);
        assertThat(binding.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(binding.getValue().finalizationVersion()).isEqualTo(3L);
        assertThat(binding.getValue().analysisRunId()).isEqualTo(RUN_ID);
        assertThat(binding.getValue().analysisSpecVersion()).isEqualTo("meeting-intelligence-v1");
        verify(accessAuditService, never()).recordList(any(), any(), any(), anyInt());
    }

    @Test
    void read_heldSnapshotIsExplicitButReleasedHoldPendingErasureIsNotDisclosed() {
        List<TranscriptSegment> segments = List.of(segment("held", 0.0, 1.0));
        TranscriptFinalization finalization = finalization(segments, true, true);
        TranscriptSessionErasureTombstone tombstone =
                tombstone(TranscriptSessionErasureStatus.HELD);
        when(erasureTombstones.findByTenantIdAndMeetingIdAndSessionId(
                TENANT_ID, MEETING_ID, SESSION_ID)).thenReturn(Optional.of(tombstone));
        when(finalizationRepository.findVisibleAnalysisOccurrence(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, RUN_ID)).thenReturn(Optional.of(finalization));

        var held = service.read(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, TENANT_ID, RUN_ID,
                "meeting-intelligence-v1", "meeting-service");
        assertThat(held.state()).isEqualTo("LEGAL_HOLD");

        finalization.setLegalHold(false);
        assertThatThrownBy(() -> service.read(
                        TENANT_ID, MEETING_ID, SESSION_ID, 3L,
                        TENANT_ID, RUN_ID, "meeting-intelligence-v1", "meeting-service"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(423);
                    assertThat(ex.getReason()).isEqualTo("TRANSCRIPT_ERASURE_PENDING");
                });
    }

    @Test
    void completedErasureBlocksSnapshotAndCapabilityBeforeContentLookupOrMint() {
        when(erasureTombstones.findByTenantIdAndMeetingIdAndSessionId(
                TENANT_ID, MEETING_ID, SESSION_ID))
                .thenReturn(Optional.of(tombstone(TranscriptSessionErasureStatus.COMPLETE)));

        assertThatThrownBy(() -> service.read(
                        TENANT_ID, MEETING_ID, SESSION_ID, 3L,
                        TENANT_ID, RUN_ID, "meeting-intelligence-v1", "meeting-service"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(410);
                    assertThat(ex.getReason()).isEqualTo("TRANSCRIPT_ERASED");
                });
        assertThatThrownBy(() -> service.issueAnalysisCapability(
                        TENANT_ID, MEETING_ID, SESSION_ID, 3L, TENANT_ID,
                        RUN_ID, "meeting-intelligence-v1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(410));

        verify(finalizationRepository, never()).findVisibleAnalysisOccurrence(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(capabilityIssuer, never()).issue(any());
    }

    @Test
    void read_wrongTenantOrMissingExactVersion_failsWithoutIssuingCapability() {
        assertThatThrownBy(() -> service.read(
                        TENANT_ID, MEETING_ID, SESSION_ID, 3L,
                        UUID.randomUUID(), RUN_ID, "meeting-intelligence-v1", "meeting-ai"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(403));

        when(finalizationRepository.findVisibleAnalysisOccurrence(
                TENANT_ID, MEETING_ID, SESSION_ID, 9L, RUN_ID))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.read(
                        TENANT_ID, MEETING_ID, SESSION_ID, 9L,
                        TENANT_ID, RUN_ID, "meeting-intelligence-v1", "meeting-ai"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));

        verify(capabilityIssuer, never()).issue(any());
        verify(accessAuditService, never()).recordList(any(), any(), any(), anyInt());
    }

    @Test
    void issue_withoutProducerMintedRunIdentity_failsClosed() {
        UUID callerChosenRun = UUID.randomUUID();
        when(finalizationRepository.findVisibleAnalysisOccurrence(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, callerChosenRun))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issueAnalysisCapability(
                        TENANT_ID, MEETING_ID, SESSION_ID, 3L, TENANT_ID,
                        callerChosenRun, "meeting-intelligence-v1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));

        verify(capabilityIssuer, never()).issue(any());
        verify(accessAuditService, never()).recordList(any(), any(), any(), anyInt());
    }

    @Test
    void read_mutatedOrPurgedSnapshot_failsBeforeCapabilityAndAudit() {
        List<TranscriptSegment> original = List.of(segment("orijinal", 0.0, 1.0));
        TranscriptFinalization finalization = finalization(original, false, false);
        List<TranscriptSegment> mutated = List.of(segment("değişmiş", 0.0, 1.0));
        when(finalizationRepository.findVisibleAnalysisOccurrence(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, RUN_ID))
                .thenReturn(Optional.of(finalization));
        when(segmentRepository.findCanonicalFinalizedSession(
                TENANT_ID, MEETING_ID, SESSION_ID)).thenReturn(mutated);

        assertThatThrownBy(() -> service.read(
                        TENANT_ID, MEETING_ID, SESSION_ID, 3L,
                        TENANT_ID, RUN_ID, "meeting-intelligence-v1", "meeting-ai"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    assertThat(ex.getReason()).isEqualTo("FINALIZATION_INTEGRITY_MISMATCH");
                });

        verify(capabilityIssuer, never()).issue(any());
        verify(accessAuditService, never()).recordList(any(), any(), any(), anyInt());
    }

    @Test
    void read_legacyMachineOccurrence_acceptsDraftProjectionOnlyWhenSourceHashMatches() {
        TranscriptSegment draft = segment(null, 0.0, 1.0);
        draft.setStatus(TranscriptSegmentStatus.DRAFT);
        draft.setTextDraft("makine anlık görüntüsü");
        List<TranscriptSegment> segments = List.of(draft);
        TranscriptFinalization finalization = finalization(segments, false, false);
        finalization.setSnapshotSha256(snapshotHasher.machineSnapshot(segments).sha256());
        when(finalizationRepository.findVisibleAnalysisOccurrence(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, RUN_ID))
                .thenReturn(Optional.of(finalization));
        when(segmentRepository.findCanonicalFinalizedSession(
                TENANT_ID, MEETING_ID, SESSION_ID)).thenReturn(segments);
        var result = service.read(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, TENANT_ID, RUN_ID,
                "meeting-intelligence-v1", "meeting-ai");

        assertThat(result.transcript()).isEqualTo("makine anlık görüntüsü");
        assertThat(result.segments()).extracting("text")
                .containsExactly("makine anlık görüntüsü");
    }

    @Test
    void read_tamperedPersistedProjection_failsClosedWithoutLegacyFallback() {
        List<TranscriptSegment> segments = List.of(segment("orijinal", 0.0, 1.0));
        TranscriptFinalization finalization = finalization(segments, false, true);
        finalization.setCanonicalTranscript("değiştirilmiş");
        when(finalizationRepository.findVisibleAnalysisOccurrence(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, RUN_ID))
                .thenReturn(Optional.of(finalization));

        assertThatThrownBy(() -> service.read(
                        TENANT_ID, MEETING_ID, SESSION_ID, 3L, TENANT_ID, RUN_ID,
                        "meeting-intelligence-v1", "meeting-ai"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    assertThat(ex.getReason()).isEqualTo("FINALIZATION_INTEGRITY_MISMATCH");
                });

        verify(segmentRepository, never()).findCanonicalFinalizedSession(any(), any(), any());
        verify(capabilityIssuer, never()).issue(any());
        verify(accessAuditService, never()).recordList(any(), any(), any(), anyInt());
    }

    @Test
    void issue_unapprovedSpec_failsBeforeRepositoryCapabilityAndAudit() {
        assertThatThrownBy(() -> service.issueAnalysisCapability(
                        TENANT_ID, MEETING_ID, SESSION_ID, 3L, TENANT_ID, RUN_ID,
                        "analysis-v2"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(403);
                    assertThat(ex.getReason()).isEqualTo("ANALYSIS_SPEC_VERSION_NOT_ALLOWED");
                });

        verify(finalizationRepository, never()).findVisibleAnalysisOccurrence(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(capabilityIssuer, never()).issue(any());
        verify(accessAuditService, never()).recordList(any(), any(), any(), anyInt());
    }

    @Test
    void read_unapprovedSpec_failsBeforeRepositoryContentAndAudit() {
        assertThatThrownBy(() -> service.read(
                        TENANT_ID, MEETING_ID, SESSION_ID, 3L, TENANT_ID, RUN_ID,
                        "analysis-v2", "meeting-ai"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(403);
                    assertThat(ex.getReason()).isEqualTo("ANALYSIS_SPEC_VERSION_NOT_ALLOWED");
                });

        verify(finalizationRepository, never()).findVisibleAnalysisOccurrence(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
        verify(capabilityIssuer, never()).issue(any());
        verify(accessAuditService, never()).recordList(any(), any(), any(), anyInt());
    }

    @Test
    void read_wrongProducerRunFailsWithoutDisclosingAnotherRunSnapshot() {
        final UUID wrongRunId = UUID.randomUUID();
        when(finalizationRepository.findVisibleAnalysisOccurrence(
                TENANT_ID, MEETING_ID, SESSION_ID, 3L, wrongRunId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read(
                        TENANT_ID, MEETING_ID, SESSION_ID, 3L, TENANT_ID, wrongRunId,
                        "meeting-intelligence-v1", "meeting-ai"))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex ->
                        assertThat(ex.getStatusCode().value()).isEqualTo(404));

        verify(capabilityIssuer, never()).issue(any());
        verify(accessAuditService, never()).recordList(any(), any(), any(), anyInt());
    }

    private TranscriptFinalization finalization(
            List<TranscriptSegment> segments, boolean legalHold, boolean persistProjection) {
        TranscriptFinalization finalization = new TranscriptFinalization();
        finalization.setId(UUID.randomUUID());
        finalization.setTenantId(TENANT_ID);
        finalization.setOrgId(TENANT_ID);
        finalization.setMeetingId(MEETING_ID);
        finalization.setSessionId(SESSION_ID);
        finalization.setFinalizationVersion(3L);
        finalization.setAnalysisRunId(RUN_ID);
        finalization.setSegmentCount(segments.size());
        TranscriptSnapshotHasher.Snapshot sourceSnapshot;
        try {
            sourceSnapshot = snapshotHasher.editorialSnapshot(segments);
        } catch (TranscriptSnapshotHasher.InvalidSnapshotException ex) {
            sourceSnapshot = snapshotHasher.machineSnapshot(segments);
        }
        finalization.setSnapshotSha256(sourceSnapshot.sha256());
        finalization.setFinalizedAt(FINALIZED_AT);
        finalization.setLegalHold(legalHold);
        if (persistProjection) {
            var stored = snapshotCodec.captureEditorial(segments);
            finalization.setCanonicalTranscript(stored.transcript());
            finalization.setCanonicalTranscriptSha256(stored.transcriptSha256());
            finalization.setCanonicalSegments(stored.canonicalSegments());
            finalization.setCanonicalProjectionSha256(stored.canonicalProjectionSha256());
        }
        return finalization;
    }

    private static TranscriptSegment segment(String text, double start, double end) {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setId(UUID.randomUUID());
        segment.setTenantId(TENANT_ID);
        segment.setOrgId(TENANT_ID);
        segment.setMeetingId(MEETING_ID);
        segment.setSessionId(SESSION_ID);
        segment.setStartTime(start);
        segment.setEndTime(end);
        segment.setTextFinal(text);
        segment.setStatus(TranscriptSegmentStatus.FINALIZED);
        return segment;
    }

    private static TranscriptSessionErasureTombstone tombstone(
            TranscriptSessionErasureStatus status) {
        TranscriptSessionErasureTombstone tombstone = new TranscriptSessionErasureTombstone();
        tombstone.setId(UUID.randomUUID());
        tombstone.setTenantId(TENANT_ID);
        tombstone.setOrgId(TENANT_ID);
        tombstone.setMeetingId(MEETING_ID);
        tombstone.setSessionId(SESSION_ID);
        tombstone.setStatus(status);
        return tombstone;
    }
}
