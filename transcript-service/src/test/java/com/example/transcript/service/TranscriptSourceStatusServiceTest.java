package com.example.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.common.meeting.events.RecordingOutcome;
import com.example.transcript.dto.TranscriptSourceStatusDto;
import com.example.transcript.dto.TranscriptSourceStatusDto.State;
import com.example.transcript.finalization.TranscriptFinalizationProperties;
import com.example.transcript.finalization.TranscriptFinalizationStateMachine;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptFinalizationState;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionErasureStatus;
import com.example.transcript.model.TranscriptSessionErasureTombstone;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.repository.TranscriptSessionErasureTombstoneRepository;
import com.example.transcript.security.AdminTenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class TranscriptSourceStatusServiceTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private static final AdminTenantContext CONTEXT = new AdminTenantContext(TENANT, "meeting-service", "meeting-service");

    @Mock private TranscriptSessionAssociationRepository associations;
    @Mock private TranscriptFinalizationRepository finalizations;
    @Mock private TranscriptSessionErasureTombstoneRepository erasures;
    @Mock private SessionErasureFence fence;
    @Mock private TranscriptAccessAuditService audit;
    private TranscriptSourceStatusService service;

    @BeforeEach
    void setUp() {
        service = new TranscriptSourceStatusService(associations, finalizations, erasures, fence, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void missingAssociationIsUnknownNotProcessingOrExpiredAndStillAudited() {
        var result = read();
        assertThat(result).isEqualTo(new TranscriptSourceStatusDto(TENANT, MEETING, SESSION,
                State.UNKNOWN, null, null, NOW, null, RecordingOutcome.UNKNOWN, null, null));
        verify(audit).recordStatus(CONTEXT, MEETING, SESSION);
        verifyNoInteractions(finalizations);
    }

    @Test
    void rejectsTenantMismatchBeforeAnyReadOrAudit() {
        assertThatThrownBy(() -> service.read(TENANT, MEETING, SESSION, UUID.randomUUID(), "meeting-service"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        verifyNoInteractions(associations, finalizations, erasures, fence, audit);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void requiresServiceAuditIdentity(String subject) {
        assertThatThrownBy(() -> service.read(TENANT, MEETING, SESSION, TENANT, subject))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("SERVICE_SUBJECT_REQUIRED");
        verifyNoInteractions(associations, finalizations, erasures, fence, audit);
    }

    @ParameterizedTest
    @EnumSource(TranscriptSessionErasureStatus.class)
    void erasureTakesPrecedenceOverAbsentOrHeldSource(TranscriptSessionErasureStatus status) {
        var tombstone = new TranscriptSessionErasureTombstone();
        tombstone.setStatus(status);
        when(erasures.findByTenantIdAndMeetingIdAndSessionId(TENANT, MEETING, SESSION))
                .thenReturn(Optional.of(tombstone));
        assertThatThrownBy(this::read).isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value())
                        .isEqualTo(status == TranscriptSessionErasureStatus.COMPLETE ? 410 : 423));
        var order = inOrder(fence, erasures);
        order.verify(fence).lock("canonical|" + TENANT + "|" + MEETING + "|" + SESSION);
        order.verify(erasures).findByTenantIdAndMeetingIdAndSessionId(TENANT, MEETING, SESSION);
        verifyNoInteractions(associations, finalizations, audit);
    }

    @Test
    void awaitingClosureDoesNotInventMicrophoneOrAiState() {
        var association = association(TranscriptFinalizationState.AWAITING_FINISH);
        association.setFinalizationCycleVersion(0);
        var result = read();
        assertThat(result.state()).isEqualTo(State.AWAITING_CLOSURE);
        assertThat(result.recordingOutcome()).isEqualTo(RecordingOutcome.UNKNOWN);
        assertThat(result.observationRevision()).isEqualTo(7);
        assertThat(result.observedAt()).isEqualTo(NOW);
        assertThat(result.finalizedOccurrence()).isNull();
        verifyNoInteractions(finalizations);
    }

    @ParameterizedTest
    @EnumSource(TranscriptSourceStatusDto.FailureCode.class)
    void reportsOnlyPersistedCycleFailure(TranscriptSourceStatusDto.FailureCode code) {
        var association = association(TranscriptFinalizationState.TIMED_OUT);
        association.setRecordingClosure(RecordingOutcome.INCOMPLETE, "CLOSURE_UNCONFIRMED");
        association.setFinalizationErrorCode(code.name());
        var result = read();
        assertThat(result.state()).isEqualTo(State.FAILED);
        assertThat(result.cycleVersion()).isEqualTo(3);
        assertThat(result.failureCode()).isEqualTo(code);
        assertThat(result.recordingIncompleteReason()).isEqualTo("CLOSURE_UNCONFIRMED");
        assertThat(result.finalizedOccurrence()).isNull();
        verifyNoInteractions(finalizations);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"SECRET request body", "no_valid_segments_before_deadline"})
    void arbitraryStoredErrorsNeverLeak(String code) {
        var association = association(TranscriptFinalizationState.TIMED_OUT);
        association.setRecordingClosure(RecordingOutcome.FINISHED, null);
        association.setFinalizationErrorCode(code);
        assertThat(read().failureCode()).isNull();
    }

    @Test
    void lateDistinctContentReopensNewCycleAndDoesNotReturnOldFailure() {
        var association = association(TranscriptFinalizationState.TIMED_OUT);
        association.setRecordingClosure(RecordingOutcome.FINISHED, null);
        association.setRecordingFinishedAt(NOW.minusSeconds(100));
        association.setFinalizationVersion(2);
        association.setFinalizationErrorCode("INVALID_CANONICAL_SEGMENT");
        assertThat(read().state()).isEqualTo(State.FAILED);
        new TranscriptFinalizationStateMachine(new TranscriptFinalizationProperties())
                .recordDistinctContent(association, NOW);
        var result = read();
        assertThat(result.state()).isEqualTo(State.QUIESCING);
        assertThat(result.cycleVersion()).isEqualTo(4);
        assertThat(result.failureCode()).isNull();
        assertThat(result.finalizedOccurrence()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = RecordingOutcome.class, names = {"FINISHED", "INCOMPLETE"})
    void finalizedReturnsExactOccurrenceAndClosure(RecordingOutcome outcome) {
        var association = association(TranscriptFinalizationState.FINALIZED);
        String reason = outcome == RecordingOutcome.INCOMPLETE ? "CLOSURE_UNCONFIRMED" : null;
        association.setFinalizationVersion(3);
        association.setRecordingClosure(outcome, reason);
        var snapshot = snapshot();
        snapshot.setRecordingClosure(outcome, reason);
        retained(snapshot);
        var result = read();
        assertThat(result.state()).isEqualTo(State.FINALIZED);
        assertThat(result.finalizedOccurrence()).isEqualTo(
                new TranscriptSourceStatusDto.FinalizedOccurrence(3, RUN, NOW.minusSeconds(60), outcome, reason));
        assertThat(result.recordingOutcome()).isEqualTo(outcome);
        assertThat(result.recordingIncompleteReason()).isEqualTo(reason);
        var order = inOrder(associations, finalizations, audit);
        order.verify(associations).findCanonicalForUpdate(TENANT, MEETING, SESSION);
        order.verify(finalizations).findVisibleOccurrenceForStatus(TENANT, MEETING, SESSION, 3);
        order.verify(audit).recordStatus(CONTEXT, MEETING, SESSION);
    }

    @Test
    void finalizedAssociationWithoutRetainedSnapshotIsUnknownWithNoExpiryClaim() {
        var association = association(TranscriptFinalizationState.FINALIZED);
        association.setFinalizationVersion(3);
        association.setRecordingClosure(RecordingOutcome.FINISHED, null);
        var result = read();
        assertThat(result.state()).isEqualTo(State.UNKNOWN);
        assertThat(result.cycleVersion()).isEqualTo(3);
        assertThat(result.observationRevision()).isEqualTo(7);
        assertThat(result.finalizedOccurrence()).isNull();
    }

    @Test
    void rejectsContradictoryCycleWithoutReadingOlderSnapshot() {
        var association = association(TranscriptFinalizationState.FINALIZED);
        association.setFinalizationVersion(2);
        association.setRecordingClosure(RecordingOutcome.FINISHED, null);
        assertThatThrownBy(this::read).hasMessageContaining("SOURCE_STATUS_INTEGRITY_MISMATCH");
        verifyNoInteractions(finalizations, audit);
    }

    @Test
    void rejectsDifferentClosureOnImmutableOccurrence() {
        var association = association(TranscriptFinalizationState.FINALIZED);
        association.setFinalizationVersion(3);
        association.setRecordingClosure(RecordingOutcome.INCOMPLETE, "CLOSURE_UNCONFIRMED");
        var snapshot = snapshot();
        snapshot.setRecordingClosure(RecordingOutcome.FINISHED, null);
        retained(snapshot);
        assertThatThrownBy(this::read).hasMessageContaining("SOURCE_STATUS_INTEGRITY_MISMATCH");
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsSnapshotWithoutProducerRun() {
        var association = association(TranscriptFinalizationState.FINALIZED);
        association.setFinalizationVersion(3);
        association.setRecordingClosure(RecordingOutcome.FINISHED, null);
        var snapshot = snapshot();
        snapshot.setAnalysisRunId(null);
        retained(snapshot);
        assertThatThrownBy(this::read).hasMessageContaining("SOURCE_STATUS_INTEGRITY_MISMATCH");
        verifyNoInteractions(audit);
    }

    @Test
    void auditFailurePreventsStatusResponse() {
        when(audit.recordStatus(any(), eq(MEETING), eq(SESSION)))
                .thenThrow(new IllegalStateException("audit unavailable"));
        assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
    }

    @Test
    void responseNeverSerializesSourceTextOrResolverMetadata() throws Exception {
        var association = association(TranscriptFinalizationState.FINALIZED);
        association.setFinalizationVersion(3);
        association.setRecordingClosure(RecordingOutcome.FINISHED, null);
        var snapshot = snapshot();
        snapshot.setCanonicalTranscript("private transcript");
        snapshot.setCanonicalSegments("private segments");
        retained(snapshot);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(read());
        assertThat(json).doesNotContain("private", "segments", "transcriptSha256", "sourceSessionId",
                "lastErrorCode", "failedAt", "AI_RUNNING", "AVAILABLE");
    }

    @Test
    void editorialSnapshotBeforeClosureDoesNotClaimCurrentSourceFinalization() {
        var association = association(TranscriptFinalizationState.FINALIZED);
        association.setFinalizationVersion(3);
        new TranscriptFinalizationStateMachine(new TranscriptFinalizationProperties())
                .recordDistinctContent(association, NOW);
        assertThat(association.getFinalizationState()).isEqualTo(TranscriptFinalizationState.FINALIZED);
        var result = read();
        assertThat(result.state()).isEqualTo(State.AWAITING_CLOSURE);
        assertThat(result.finalizedOccurrence()).isNull();
        verifyNoInteractions(finalizations);
    }

    @Test
    void historicalSnapshotRetainsUnknownProvenanceAfterAssociationClosureBackfill() {
        var association = association(TranscriptFinalizationState.FINALIZED);
        association.setFinalizationVersion(3);
        association.setRecordingClosure(RecordingOutcome.FINISHED, null);
        retained(snapshot());
        var result = read();
        assertThat(result.state()).isEqualTo(State.FINALIZED);
        assertThat(result.recordingOutcome()).isEqualTo(RecordingOutcome.FINISHED);
        assertThat(result.finalizedOccurrence().recordingOutcome()).isEqualTo(RecordingOutcome.UNKNOWN);
    }

    private TranscriptSourceStatusDto read() {
        return service.read(TENANT, MEETING, SESSION, TENANT, "meeting-service");
    }

    private TranscriptSessionAssociation association(TranscriptFinalizationState state) {
        var association = new TranscriptSessionAssociation();
        association.setFinalizationState(state);
        association.setFinalizationCycleVersion(3);
        ReflectionTestUtils.setField(association, "version", 7L);
        ReflectionTestUtils.setField(association, "updatedAt", NOW.minusSeconds(10));
        when(associations.findCanonicalForUpdate(TENANT, MEETING, SESSION)).thenReturn(Optional.of(association));
        return association;
    }

    private TranscriptFinalization snapshot() {
        var snapshot = new TranscriptFinalization();
        snapshot.setTenantId(TENANT);
        snapshot.setOrgId(TENANT);
        snapshot.setMeetingId(MEETING);
        snapshot.setSessionId(SESSION);
        snapshot.setFinalizationVersion(3);
        snapshot.setAnalysisRunId(RUN);
        snapshot.setFinalizedAt(NOW.minusSeconds(60));
        return snapshot;
    }

    private void retained(TranscriptFinalization snapshot) {
        when(finalizations.findVisibleOccurrenceForStatus(TENANT, MEETING, SESSION, 3))
                .thenReturn(Optional.of(snapshot));
    }
}
