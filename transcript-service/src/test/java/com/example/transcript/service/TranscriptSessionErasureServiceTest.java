package com.example.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptSessionErasureAudit;
import com.example.transcript.model.TranscriptSessionErasureStatus;
import com.example.transcript.model.TranscriptSessionErasureTombstone;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.repository.TranscriptSessionErasureAuditRepository;
import com.example.transcript.repository.TranscriptSessionErasureTombstoneRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TranscriptSessionErasureServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Mock private TranscriptSessionErasureTombstoneRepository tombstones;
    @Mock private TranscriptSessionErasureAuditRepository audits;
    @Mock private TranscriptSessionAssociationRepository associations;
    @Mock private TranscriptFinalizationRepository finalizations;
    @Mock private TranscriptSegmentRepository segments;
    @Mock private SessionErasureFence fence;
    @Mock private TranscriptSessionErasureTombstoneStore tombstoneStore;

    private TranscriptSessionErasureService service;
    private TranscriptSessionErasureTombstone tombstone;

    @BeforeEach
    void setUp() {
        service = new TranscriptSessionErasureService(
                tombstones, audits, associations, finalizations, segments,
                fence, tombstoneStore, Clock.fixed(NOW, ZoneOffset.UTC));
        tombstone = tombstone(TranscriptSessionErasureStatus.HELD);
        when(tombstones.findSessionForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(Optional.of(tombstone));
    }

    @Test
    void legalHoldKeepsPermanentFenceAndReturnsHeldWithoutDeleting() {
        TranscriptFinalization held = new TranscriptFinalization();
        held.setLegalHold(true);
        when(tombstones.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(finalizations.findErasureScopeForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(List.of(held));

        var result = service.erase(TENANT, MEETING, SESSION, "REC-42");

        assertThat(result.status()).isEqualTo(TranscriptSessionErasureStatus.HELD);
        verify(tombstoneStore).observe(any(), org.mockito.ArgumentMatchers.eq("REC-42"));
        verify(finalizations, never()).deleteErasureScope(any(), any(), any());
        verify(segments, never()).deleteCanonicalErasureScope(any(), any(), any());
        verify(audits).save(any(TranscriptSessionErasureAudit.class));
    }

    @Test
    void releasedHoldDeletesCanonicalLegacyAndAssociationScopesThenCompletes() {
        when(tombstones.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(finalizations.findErasureScopeForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(List.of());
        when(finalizations.deleteErasureScope(TENANT, MEETING, SESSION)).thenReturn(1);
        when(segments.deleteCanonicalErasureScope(TENANT, MEETING, SESSION)).thenReturn(2);
        when(segments.deleteLegacySourceErasureScope(TENANT, MEETING, "REC-42")).thenReturn(3);
        when(associations.deleteErasureScope(TENANT, MEETING, SESSION, "REC-42")).thenReturn(1);

        var result = service.erase(TENANT, MEETING, SESSION, "REC-42");

        assertThat(result.status()).isEqualTo(TranscriptSessionErasureStatus.COMPLETE);
        assertThat(result.deletedCount()).isEqualTo(7);
        assertThat(tombstone.getCompletedAt()).isEqualTo(NOW);
        verify(finalizations).existsLegalHoldForErasure(TENANT, MEETING, SESSION);
        verify(audits).save(any(TranscriptSessionErasureAudit.class));
    }

    @Test
    void completeRequestIsIdempotentAndDoesNotDeleteTwice() {
        tombstone = tombstone(TranscriptSessionErasureStatus.COMPLETE);
        tombstone.setSegmentDeletedCount(2);
        tombstone.setCompletedAt(NOW.minusSeconds(1));
        when(tombstones.findSessionForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(Optional.of(tombstone));

        var result = service.erase(TENANT, MEETING, SESSION, "REC-42");

        assertThat(result.deletedCount()).isEqualTo(2);
        verify(finalizations, never()).findErasureScopeForUpdate(any(), any(), any());
        verify(segments, never()).deleteCanonicalErasureScope(any(), any(), any());
    }

    private static TranscriptSessionErasureTombstone tombstone(
            TranscriptSessionErasureStatus status) {
        TranscriptSessionErasureTombstone row = new TranscriptSessionErasureTombstone();
        row.setId(UUID.randomUUID());
        row.setTenantId(TENANT);
        row.setOrgId(TENANT);
        row.setMeetingId(MEETING);
        row.setSessionId(SESSION);
        row.setSourceSessionHash(SessionErasureFence.sourceHash("REC-42"));
        row.setStatus(status);
        row.setRequestedAt(NOW.minusSeconds(10));
        row.setUpdatedAt(NOW.minusSeconds(10));
        return row;
    }
}
