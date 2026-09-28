package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.common.meeting.events.RecordingOutcome;
import com.example.meeting.dto.v1.admin.MeetingSessionProcessingStatusResponse.SavedState;
import com.example.meeting.model.*;
import com.example.meeting.repository.*;
import com.example.meeting.security.AdminTenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

class MeetingSessionProcessingStatusServiceTest {
    private static final UUID TENANT = UUID.randomUUID(), MEETING = UUID.randomUUID(), SESSION = UUID.randomUUID();
    private static final Instant SOURCE_TIME = Instant.parse("2026-09-26T18:55:00Z");
    private static final Instant READ_TIME = SOURCE_TIME.plusSeconds(2);
    private static final AdminTenantContext CONTEXT = new AdminTenantContext(TENANT, "stable-owner", "module-user");
    private final MeetingCanonicalTranscriptService auth = mock(MeetingCanonicalTranscriptService.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final MeetingSessionRepository sessions = mock(MeetingSessionRepository.class);
    private final MeetingSessionErasureRepository erasures = mock(MeetingSessionErasureRepository.class);
    private final MeetingAnalysisRunRepository results = mock(MeetingAnalysisRunRepository.class);
    private final TranscriptSourceStatusClient source = mock(TranscriptSourceStatusClient.class);
    private final MeetingIntelligenceResultAccessAuditService audit = mock(MeetingIntelligenceResultAccessAuditService.class);
    private final CountingTransactions transactions = new CountingTransactions();
    private MeetingSessionProcessingStatusService service;

    @BeforeEach
    void setup() {
        service = new MeetingSessionProcessingStatusService(auth, meetings, sessions, erasures, results,
                source, audit, transactions, Clock.fixed(READ_TIME, ZoneOffset.UTC));
        when(meetings.findVisibleToOrgAndIdForUpdate(TENANT, MEETING)).thenReturn(Optional.of(new Meeting()));
        when(sessions.findByIdAndMeetingIdVisibleToOrg(SESSION, MEETING, TENANT))
                .thenReturn(Optional.of(new MeetingSession()));
        when(source.read(TENANT, MEETING, SESSION)).thenReturn(observation(TranscriptSourceStatusClient.State.QUIESCING, 2L, null));
    }

    @Test
    void absentSavedResultIsAuditedWithoutInventingAnAnalysisRunOrAiActivity() {
        var response = service.read(CONTEXT, MEETING, SESSION);
        assertThat(response.savedResult().state()).isEqualTo(SavedState.NOT_FOUND);
        assertThat(response.savedResult().analysisRunId()).isNull();
        assertThat(response.savedResult().matchesCurrentSourceOccurrence()).isNull();
        assertThat(response.source().observedAt()).isEqualTo(SOURCE_TIME);
        assertThat(response.savedResult().observedAt()).isEqualTo(READ_TIME);
        verify(audit).recordProcessingStatus(CONTEXT, MEETING, SESSION);
        verify(results).findLatestBySessionVisibleToOrgForStatus(MEETING, TENANT, SESSION.toString());
        verifyNoMoreInteractions(results);
        assertThat(transactions.commits).isEqualTo(1);
    }

    @Test
    void remoteAuthorizationAndReadHappenOutsideTheResultTransaction() {
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(auth).requireOwnerAccess(CONTEXT, MEETING);
        when(source.read(TENANT, MEETING, SESSION)).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return observation(TranscriptSourceStatusClient.State.QUIESCING, 2L, null);
        });
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }).when(audit).recordProcessingStatus(CONTEXT, MEETING, SESSION);
        service.read(CONTEXT, MEETING, SESSION);
        var order = inOrder(auth, erasures, sessions, source, meetings, results, audit);
        order.verify(auth).requireOwnerAccess(CONTEXT, MEETING);
        order.verify(erasures).findById(SESSION);
        order.verify(sessions).findByIdAndMeetingIdVisibleToOrg(SESSION, MEETING, TENANT);
        order.verify(source).read(TENANT, MEETING, SESSION);
        order.verify(meetings).findVisibleToOrgAndIdForUpdate(TENANT, MEETING);
        order.verify(erasures).findById(SESSION);
        order.verify(sessions).findByIdAndMeetingIdVisibleToOrg(SESSION, MEETING, TENANT);
        order.verify(results).findLatestBySessionVisibleToOrgForStatus(MEETING, TENANT, SESSION.toString());
        order.verify(audit).recordProcessingStatus(CONTEXT, MEETING, SESSION);
    }

    @Test
    void authorizationDenialPrecedesAllMetadataAndRemoteReads() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(auth).requireOwnerAccess(CONTEXT, MEETING);
        fails(403);
        verifyNoInteractions(sessions, erasures, source, meetings, results, audit);
    }

    @Test
    void missingExactSessionNeverFallsBackToAnotherSession() {
        when(sessions.findByIdAndMeetingIdVisibleToOrg(SESSION, MEETING, TENANT)).thenReturn(Optional.empty());
        fails(404);
        verifyNoInteractions(source, results, audit);
    }

    @ParameterizedTest
    @EnumSource(MeetingSessionErasureStatus.class)
    void existingErasureTakesPrecedenceOverDeletedSession(MeetingSessionErasureStatus state) {
        when(erasures.findById(SESSION)).thenReturn(Optional.of(erasure(state)));
        fails(state == MeetingSessionErasureStatus.COMPLETE ? 410 : 423);
        verifyNoInteractions(sessions, source, results, audit);
    }

    @ParameterizedTest
    @EnumSource(MeetingSessionErasureStatus.class)
    void erasureDuringRemoteReadDeniesTheResponse(MeetingSessionErasureStatus state) {
        when(erasures.findById(SESSION)).thenReturn(Optional.empty(), Optional.of(erasure(state)));
        fails(state == MeetingSessionErasureStatus.COMPLETE ? 410 : 423);
        verify(source).read(TENANT, MEETING, SESSION);
        verifyNoInteractions(results, audit);
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test
    void upstreamOutageIsNotAnAuthoritativeMissingResult() {
        when(source.read(TENANT, MEETING, SESSION)).thenThrow(
                new TranscriptSourceStatusClient.ReadFailure(TranscriptSourceStatusClient.Failure.UNAVAILABLE));
        fails(503);
        verifyNoInteractions(results, audit);
    }

    @Test
    void alternateClientCannotReturnAnotherSessionsObservation() {
        var valid = observation(TranscriptSourceStatusClient.State.QUIESCING, 2L, null);
        when(source.read(TENANT, MEETING, SESSION)).thenReturn(new TranscriptSourceStatusClient.Observation(
                TENANT, MEETING, UUID.randomUUID(), valid.state(), 2L, 8L, SOURCE_TIME,
                null, RecordingOutcome.FINISHED, null, null));
        fails(409);
        verifyNoInteractions(results, audit);
    }

    @Test
    void matchingResultUsesImmutableProvenanceRatherThanBackfilledAssociationClosure() {
        var run = run(2L);
        saved(run);
        when(source.read(TENANT, MEETING, SESSION)).thenReturn(observation(
                TranscriptSourceStatusClient.State.FINALIZED, 2L, occurrence(run)));
        var response = service.read(CONTEXT, MEETING, SESSION);
        assertThat(response.savedResult().matchesCurrentSourceOccurrence()).isTrue();
        assertThat(response.savedResult().recordingOutcome()).isEqualTo(RecordingOutcome.UNKNOWN);
        assertThat(response.source().recordingOutcome()).isEqualTo(RecordingOutcome.FINISHED);
    }

    @Test
    void serializesCompatibilityObservationForExistingMobileParser() throws Exception {
        var run = run(2L);
        saved(run);
        when(source.read(TENANT, MEETING, SESSION)).thenReturn(observation(
                TranscriptSourceStatusClient.State.FINALIZED, 2L, occurrence(run)));
        var response = service.read(CONTEXT, MEETING, SESSION);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "status-compatibility-response.json"),
                mapper.writeValueAsString(response));
        assertThat(response.savedResult().recordingOutcome()).isEqualTo(RecordingOutcome.UNKNOWN);
        assertThat(response.source().finalizedOccurrence().recordingOutcome()).isEqualTo(RecordingOutcome.UNKNOWN);
        assertThat(response.savedResult().matchesCurrentSourceOccurrence()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = TranscriptSourceStatusClient.State.class, names = {"QUIESCING", "FAILED", "UNKNOWN"})
    void olderSavedResultRemainsVisibleButDoesNotMatchNewCycle(TranscriptSourceStatusClient.State state) {
        saved(run(1L));
        when(source.read(TENANT, MEETING, SESSION)).thenReturn(observation(state, 2L, null));
        var response = service.read(CONTEXT, MEETING, SESSION);
        assertThat(response.savedResult().state()).isEqualTo(SavedState.AVAILABLE);
        assertThat(response.savedResult().matchesCurrentSourceOccurrence()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {2L, 3L})
    void resultThatMayHaveArrivedAfterSourceObservationHasUnknownMatch(long version) {
        saved(run(version));
        assertThat(service.read(CONTEXT, MEETING, SESSION).savedResult().matchesCurrentSourceOccurrence()).isNull();
    }

    @Test
    void legacyRunHasUnknownMatch() {
        saved(run(null));
        assertThat(service.read(CONTEXT, MEETING, SESSION).savedResult().matchesCurrentSourceOccurrence()).isNull();
    }

    @Test
    void contradictoryImmutableClosureIsAConflict() {
        var run = run(2L);
        var occurrence = new TranscriptSourceStatusClient.Occurrence(run.getFinalizationVersion(),
                run.getAnalysisRunId(), run.getFinalizedAt(), RecordingOutcome.INCOMPLETE, "CLOSURE_UNCONFIRMED");
        saved(run);
        when(source.read(TENANT, MEETING, SESSION)).thenReturn(observation(
                TranscriptSourceStatusClient.State.FINALIZED, 2L, occurrence));
        fails(409);
        verifyNoInteractions(audit);
    }

    @Test
    void auditFailureRollsBackAndNeverReturnsStatus() {
        doThrow(new IllegalStateException("storage unavailable"))
                .when(audit).recordProcessingStatus(CONTEXT, MEETING, SESSION);
        assertThatThrownBy(() -> service.read(CONTEXT, MEETING, SESSION)).isInstanceOf(IllegalStateException.class);
        assertThat(transactions.commits).isZero();
        assertThat(transactions.rollbacks).isEqualTo(1);
    }

    private void fails(int status) {
        assertThatThrownBy(() -> service.read(CONTEXT, MEETING, SESSION))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value()).isEqualTo(status));
    }

    private void saved(MeetingAnalysisRun run) {
        when(results.findLatestBySessionVisibleToOrgForStatus(MEETING, TENANT, SESSION.toString()))
                .thenReturn(Optional.of(run));
    }

    private static MeetingAnalysisRun run(Long version) {
        var run = new MeetingAnalysisRun();
        run.setAnalysisRunId(UUID.randomUUID());
        run.setMeetingId(MEETING);
        run.setTenantId(TENANT);
        run.setTranscriptSessionId(SESSION.toString());
        run.setFinalizationVersion(version);
        run.setFinalizedAt(version == null ? null : SOURCE_TIME.minusSeconds(5));
        return run;
    }

    private static TranscriptSourceStatusClient.Occurrence occurrence(MeetingAnalysisRun run) {
        return new TranscriptSourceStatusClient.Occurrence(run.getFinalizationVersion(), run.getAnalysisRunId(),
                run.getFinalizedAt(), RecordingOutcome.UNKNOWN, null);
    }

    private static TranscriptSourceStatusClient.Observation observation(TranscriptSourceStatusClient.State state,
            Long cycle, TranscriptSourceStatusClient.Occurrence occurrence) {
        return new TranscriptSourceStatusClient.Observation(TENANT, MEETING, SESSION, state, cycle, 8L,
                SOURCE_TIME, null, RecordingOutcome.FINISHED, null, occurrence);
    }

    private static MeetingSessionErasure erasure(MeetingSessionErasureStatus state) {
        var row = new MeetingSessionErasure();
        row.setSessionId(SESSION);
        row.setMeetingId(MEETING);
        row.setTenantId(TENANT);
        row.setStatus(state);
        return row;
    }

    private static class CountingTransactions extends AbstractPlatformTransactionManager {
        int commits, rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
