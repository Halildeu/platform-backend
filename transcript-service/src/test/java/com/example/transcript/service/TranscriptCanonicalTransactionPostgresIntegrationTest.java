package com.example.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventPayload;
import com.example.common.meeting.events.MeetingEventType;
import com.example.common.meeting.events.MeetingEventV1Serializer;
import com.example.transcript.directstt.DirectSttTranscriptResultEvent;
import com.example.transcript.directstt.TranscriptSessionAssociationStore;
import com.example.transcript.directstt.DirectSttTranscriptIngestionService;
import com.example.transcript.events.TranscriptMeetingEventMessage;
import com.example.transcript.finalization.RecordingFinishedEvent;
import com.example.transcript.finalization.RecordingFinishedEventProcessor;
import com.example.transcript.finalization.FinalizedTranscriptSnapshotCodec;
import com.example.transcript.finalization.TranscriptFinalizationStateMachine;
import com.example.transcript.finalization.TranscriptQuiescentFinalizationProcessor;
import com.example.transcript.finalization.TranscriptSnapshotHasher;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptMeetingEventInboxRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.repository.TranscriptSessionErasureTombstoneRepository;
import com.example.transcript.security.AdminTenantContext;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        TranscriptFinalizationService.class,
        TranscriptSessionAssociationStore.class,
        DirectSttTranscriptIngestionService.class,
        SessionErasureFence.class,
        TranscriptSessionErasureTombstoneStore.class,
        TranscriptSessionErasureService.class,
        RecordingFinishedEventProcessor.class,
        TranscriptQuiescentFinalizationProcessor.class,
        TranscriptFinalizationStateMachine.class,
        FinalizedTranscriptSnapshotCodec.class,
        TranscriptSnapshotHasher.class,
        com.fasterxml.jackson.databind.ObjectMapper.class,
        com.example.transcript.config.TranscriptFinalizationConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TranscriptCanonicalTransactionPostgresIntegrationTest {

    private static final String SCHEMA = "transcript_service";
    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEETING = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SESSION = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("transcript_tx")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired private TranscriptFinalizationService finalizationService;
    @Autowired private RecordingFinishedEventProcessor recordingFinishedEventProcessor;
    @Autowired private TranscriptQuiescentFinalizationProcessor quiescentFinalizationProcessor;
    @Autowired private TranscriptSessionAssociationStore associationStore;
    @Autowired private DirectSttTranscriptIngestionService directSttIngestion;
    @Autowired private TranscriptSessionErasureService erasureService;
    @Autowired private SessionErasureFence erasureFence;
    @Autowired private TranscriptSessionErasureTombstoneRepository erasureTombstones;
    @Autowired private TranscriptSessionAssociationRepository associations;
    @Autowired private TranscriptSegmentRepository segments;
    @Autowired private TranscriptFinalizationRepository finalizations;
    @Autowired private TranscriptMeetingEventInboxRepository meetingEventInbox;
    @Autowired private TranscriptEventOutboxRepository outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM " + SCHEMA + ".transcript_session_erasure_audit");
        jdbc.update("DELETE FROM " + SCHEMA + ".transcript_session_erasure_tombstones");
        jdbc.update("DELETE FROM " + SCHEMA + ".transcript_meeting_event_inbox");
        jdbc.update("DELETE FROM " + SCHEMA + ".transcript_event_outbox");
        jdbc.update("DELETE FROM " + SCHEMA + ".transcript_finalizations");
        jdbc.update("DELETE FROM " + SCHEMA + ".transcript_segments");
        jdbc.update("DELETE FROM " + SCHEMA + ".transcript_session_associations");
    }

    @Test
    void ingestionAndErasureRaceSerializesAndLeavesPermanentFenceWithoutSegments()
            throws Exception {
        insertResolvedAssociation();
        DirectSttTranscriptResultEvent event = new DirectSttTranscriptResultEvent(
                "race-entry", TENANT, TENANT.toString(), "7", MEETING, "SES-42",
                1L, 1L, 1L, 1_000L, "race-correlation", "a".repeat(64),
                "race payload", 1.0d);
        CountDownLatch writerLocked = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                SessionErasureFence.UUIDScope scope =
                        new SessionErasureFence.UUIDScope(TENANT, MEETING, SESSION);
                erasureFence.lock(
                        SessionErasureFence.canonicalKey(scope),
                        SessionErasureFence.sourceKey(TENANT, MEETING, "SES-42"));
                writerLocked.countDown();
                try {
                    if (!releaseWriter.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("writer release timeout");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("writer interrupted", ex);
                }
                directSttIngestion.upsert(event, SESSION);
                return null;
            }));
            assertThat(writerLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> eraser = executor.submit(() ->
                    erasureService.erase(TENANT, MEETING, SESSION, "SES-42"));
            Thread.sleep(100);
            assertThat(eraser.isDone()).isFalse();
            releaseWriter.countDown();
            writer.get(15, TimeUnit.SECONDS);
            eraser.get(15, TimeUnit.SECONDS);
        } finally {
            releaseWriter.countDown();
            executor.shutdownNow();
        }

        assertThat(segments.findDirectSttSourceWindow(TENANT, MEETING, "SES-42", 1L)).isEmpty();
        assertThat(erasureTombstones.existsByTenantIdAndMeetingIdAndSessionId(
                TENANT, MEETING, SESSION)).isTrue();
        assertThat(associations.findByTenantIdAndMeetingIdAndSourceSystemAndSourceSessionId(
                TENANT, MEETING, DirectSttTranscriptResultEvent.SOURCE_SYSTEM, "SES-42")).isEmpty();
    }

    @Test
    void erasedSourceRejectsAbsentAssociationCreation() {
        erasureService.erase(TENANT, MEETING, SESSION, "SES-absent");

        assertThatThrownBy(() -> associationStore.ensurePending(
                        UUID.randomUUID(), TENANT, MEETING, "SES-absent", Instant.now()))
                .isInstanceOf(SessionErasureFence.SessionErasedException.class);
        assertThat(associations.findByTenantIdAndMeetingIdAndSourceSystemAndSourceSessionId(
                TENANT, MEETING, DirectSttTranscriptResultEvent.SOURCE_SYSTEM,
                "SES-absent")).isEmpty();
    }

    @Test
    void resolvedAssociationBackfillsLegacyRowsIdempotentlyAndOnlyWithinTenantMeeting() {
        UUID associationId = UUID.randomUUID();
        UUID claim = UUID.randomUUID();
        insertAssociation(associationId, TENANT, MEETING, "SES-legacy", null, "RESOLVING", claim, 0);
        TranscriptSegment first = saveSegment(TENANT, MEETING, null, "SES-legacy", 1L,
                TranscriptSegmentStatus.DRAFT, null);
        TranscriptSegment second = saveSegment(TENANT, MEETING, null, "SES-legacy", 2L,
                TranscriptSegmentStatus.DRAFT, null);
        UUID foreignTenant = UUID.randomUUID();
        TranscriptSegment foreign = saveSegment(foreignTenant, MEETING, null, "SES-legacy", 1L,
                TranscriptSegmentStatus.DRAFT, null);

        assertThat(associationStore.completeResolved(
                associationId, claim, TENANT, MEETING, "SES-legacy", SESSION, Instant.now()))
                .isTrue();
        assertThat(associationStore.completeResolved(
                associationId, claim, TENANT, MEETING, "SES-legacy", SESSION, Instant.now()))
                .isFalse();

        assertThat(segments.findById(first.getId()).orElseThrow().getSessionId()).isEqualTo(SESSION);
        assertThat(segments.findById(second.getId()).orElseThrow().getSessionId()).isEqualTo(SESSION);
        assertThat(segments.findById(foreign.getId()).orElseThrow().getSessionId()).isNull();
        var pinned = associations.findById(associationId).orElseThrow();
        assertThat(pinned.getStatus()).isEqualTo(TranscriptSessionAssociationStatus.RESOLVED);
        assertThat(pinned.getSessionId()).isEqualTo(SESSION);
    }

    @Test
    void duplicateFinalizationPreservesExactSerializerTextAcrossPostgresRoundTrip() {
        insertResolvedAssociation();
        saveSegment(TENANT, MEETING, SESSION, "SES-42", 1L,
                TranscriptSegmentStatus.FINALIZED, "private final transcript");

        var first = finalizationService.finalizeTranscript(context(TENANT), MEETING, SESSION, 1L);
        var duplicate = finalizationService.finalizeTranscript(context(TENANT), MEETING, SESSION, 1L);

        assertThat(duplicate).isEqualTo(first);
        assertThat(finalizations.count()).isEqualTo(1L);
        assertThat(outbox.count()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT finalization_state FROM " + SCHEMA
                        + ".transcript_session_associations WHERE tenant_id=? AND meeting_id=? "
                        + "AND session_id=?",
                String.class, TENANT, MEETING, SESSION)).isEqualTo("FINALIZED");
        assertThat(jdbc.queryForObject(
                "SELECT finalization_cycle_version FROM " + SCHEMA
                        + ".transcript_session_associations WHERE tenant_id=? AND meeting_id=? "
                        + "AND session_id=?",
                Long.class, TENANT, MEETING, SESSION)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT quiescence_due_at IS NULL FROM " + SCHEMA
                        + ".transcript_session_associations WHERE tenant_id=? AND meeting_id=? "
                        + "AND session_id=?",
                Boolean.class, TENANT, MEETING, SESSION)).isTrue();
        String expectedPayload = MeetingEventV1Serializer.toJson(MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.TRANSCRIPT_READY)
                .producer("transcript-service")
                .meetingId(MEETING)
                .tenantId(TENANT)
                .orgId(TENANT)
                .occurredAt(first.finalizedAt())
                .aggregateType("meeting.transcript")
                .aggregateId(SESSION)
                .aggregateRevision(1L)
                .payload(new MeetingEventPayload.TranscriptReady(
                        finalizations.findAll().getFirst().getAnalysisRunId(), SESSION, 1L, 1))
                .build());
        String storedPayload = jdbc.queryForObject(
                "SELECT payload FROM " + SCHEMA + ".transcript_event_outbox",
                String.class);
        assertThat(storedPayload).isEqualTo(expectedPayload);
        assertThat(storedPayload.getBytes(StandardCharsets.UTF_8))
                .containsExactly(expectedPayload.getBytes(StandardCharsets.UTF_8));

        TranscriptMeetingEventMessage rehydrated = TranscriptMeetingEventMessage.from(
                outbox.findAll().getFirst());
        assertThat(rehydrated.payloadJson()).isEqualTo(expectedPayload);
        assertThat(rehydrated.payloadJson().getBytes(StandardCharsets.UTF_8))
                .containsExactly(expectedPayload.getBytes(StandardCharsets.UTF_8));
        assertThat(rehydrated.payloadJson())
                .doesNotContain("private final transcript", "textDraft", "textFinal", "audio");
        var storedFinalization = finalizations.findAll().getFirst();
        assertThat(storedFinalization.getCanonicalTranscript())
                .isEqualTo("private final transcript");
        assertThat(storedFinalization.getCanonicalTranscriptSha256()).matches("[0-9a-f]{64}");
        assertThat(storedFinalization.getCanonicalProjectionSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void quiescentMachineFinalizationIsIdempotentAcrossPostgresRoundTrip() {
        UUID associationId = insertQuiescingAssociation(false);
        saveSegment(TENANT, MEETING, SESSION, "SES-quiescent", 1L,
                TranscriptSegmentStatus.DRAFT, null);

        assertThat(quiescentFinalizationProcessor.process(associationId))
                .isEqualTo(TranscriptQuiescentFinalizationProcessor.Outcome.READY);
        assertThat(quiescentFinalizationProcessor.process(associationId))
                .isEqualTo(TranscriptQuiescentFinalizationProcessor.Outcome.NOT_DUE);

        assertThat(finalizations.count()).isEqualTo(1L);
        assertThat(outbox.count()).isEqualTo(1L);
        var finalization = finalizations.findAll().getFirst();
        assertThat(finalization.getFinalizationVersion()).isEqualTo(1L);
        assertThat(finalization.getSegmentCount()).isEqualTo(1);
        assertThat(finalization.getSnapshotSha256()).matches("[0-9a-f]{64}");
        assertThat(finalization.getCanonicalTranscript()).isEqualTo("draft");
        assertThat(finalization.getCanonicalTranscriptSha256()).matches("[0-9a-f]{64}");
        assertThat(finalization.getCanonicalProjectionSha256()).matches("[0-9a-f]{64}");
        var event = outbox.findAll().getFirst();
        assertThat(event.getEventType()).isEqualTo("meeting.transcript.ready");
        assertThat(event.getPayload())
                .contains("\"segmentCount\":1")
                .doesNotContain("draft", "textDraft", "textFinal");
        var association = associations.findById(associationId).orElseThrow();
        assertThat(association.getFinalizationState().name()).isEqualTo("FINALIZED");
        assertThat(association.getFinalizationVersion()).isEqualTo(1L);
    }

    @Test
    void recordingFinishedInboxEnrollmentIsAtomicAndReplayIdempotent() {
        String sourceSession = "SES-finished-inbox";
        String eventKey = "meeting.recording|" + SESSION + "|meeting.recording.finished|1";
        RecordingFinishedEvent event = new RecordingFinishedEvent(
                eventKey, "a".repeat(64), TENANT, MEETING, SESSION, sourceSession,
                Instant.now().minusSeconds(1));

        assertThat(recordingFinishedEventProcessor.process(event))
                .isEqualTo(RecordingFinishedEventProcessor.ProcessResult.PROCESSED);
        assertThat(recordingFinishedEventProcessor.process(event))
                .isEqualTo(RecordingFinishedEventProcessor.ProcessResult.DUPLICATE);

        assertThat(meetingEventInbox.count()).isEqualTo(1L);
        var association = associations
                .findByTenantIdAndMeetingIdAndSourceSystemAndSourceSessionId(
                        TENANT, MEETING, DirectSttTranscriptResultEvent.SOURCE_SYSTEM, sourceSession)
                .orElseThrow();
        assertThat(association.getStatus()).isEqualTo(TranscriptSessionAssociationStatus.RESOLVED);
        assertThat(association.getSessionId()).isEqualTo(SESSION);
        assertThat(association.getFinalizationState().name()).isEqualTo("QUIESCING");
        assertThat(association.getFinalizationCycleVersion()).isEqualTo(1L);
        assertThat(association.getMinWaitAt()).isNotNull();
        assertThat(association.getMaxWaitAt()).isAfter(association.getMinWaitAt());

        RecordingFinishedEvent divergent = new RecordingFinishedEvent(
                eventKey, "b".repeat(64), TENANT, MEETING, SESSION, sourceSession,
                event.finishedAt());
        assertThatThrownBy(() -> recordingFinishedEventProcessor.process(divergent))
                .isInstanceOf(
                        RecordingFinishedEventProcessor.RecordingFinishedEventConflictException.class)
                .hasMessage("INBOX_KEY_DIVERGENCE");
        assertThat(meetingEventInbox.count()).isEqualTo(1L);
    }

    @Test
    void zeroSegmentDeadlineCreatesOnlyFailedEventAndDurableTimedOutState() {
        UUID associationId = insertQuiescingAssociation(true);

        assertThat(quiescentFinalizationProcessor.process(associationId))
                .isEqualTo(TranscriptQuiescentFinalizationProcessor.Outcome.FAILED);

        assertThat(finalizations.count()).isZero();
        assertThat(outbox.count()).isEqualTo(1L);
        assertThat(outbox.findAll().getFirst().getPayload())
                .contains("NO_VALID_SEGMENTS_BEFORE_DEADLINE")
                .doesNotContain("textDraft", "textFinal", "audio");
        var association = associations.findById(associationId).orElseThrow();
        assertThat(association.getFinalizationState().name()).isEqualTo("TIMED_OUT");
        assertThat(association.getFinalizationErrorCode())
                .isEqualTo("NO_VALID_SEGMENTS_BEFORE_DEADLINE");
    }

    @Test
    void readyOutboxCollisionRollsBackMachineFinalizationAndStateAdvance() {
        UUID associationId = insertQuiescingAssociation(false);
        saveSegment(TENANT, MEETING, SESSION, "SES-quiescent", 1L,
                TranscriptSegmentStatus.DRAFT, null);
        insertOutboxCollision("meeting.transcript|" + SESSION + "|meeting.transcript.ready|1");

        assertThatThrownBy(() -> quiescentFinalizationProcessor.process(associationId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(finalizations.count()).isZero();
        assertThat(outbox.count()).isEqualTo(1L);
        var association = associations.findById(associationId).orElseThrow();
        assertThat(association.getFinalizationState().name()).isEqualTo("QUIESCING");
        assertThat(association.getFinalizationVersion()).isZero();
    }

    @Test
    void concurrentSameVersionFinalizationSerializesToOneObservableEffect() throws Exception {
        insertResolvedAssociation();
        saveSegment(TENANT, MEETING, SESSION, "SES-42", 1L,
                TranscriptSegmentStatus.FINALIZED, "final text");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> finalizeAfterBarrier(ready, start));
            var second = executor.submit(() -> finalizeAfterBarrier(ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(20, TimeUnit.SECONDS).id());
        }

        assertThat(finalizations.count()).isEqualTo(1L);
        assertThat(outbox.count()).isEqualTo(1L);
    }

    @Test
    void outboxUniqueFailureRollsBackFinalizationAndVersionAdvance() {
        insertResolvedAssociation();
        saveSegment(TENANT, MEETING, SESSION, "SES-42", 1L,
                TranscriptSegmentStatus.FINALIZED, "final text");
        String key = "meeting.transcript|" + SESSION + "|meeting.transcript.ready|1";
        insertOutboxCollision(key);

        assertThatThrownBy(() -> finalizationService.finalizeTranscript(
                context(TENANT), MEETING, SESSION, 1L))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(finalizations.count()).isZero();
        assertThat(outbox.count()).isEqualTo(1L);
        assertThat(associations.findByTenantIdAndMeetingIdAndSourceSystemAndSourceSessionId(
                        TENANT, MEETING, DirectSttTranscriptResultEvent.SOURCE_SYSTEM, "SES-42")
                .orElseThrow().getFinalizationVersion()).isZero();
    }

    @Test
    void foreignTenantAndMissingAssociationFailClosedWithoutOutbox() {
        insertResolvedAssociation();
        UUID foreignTenant = UUID.randomUUID();

        assertThatThrownBy(() -> finalizationService.finalizeTranscript(
                context(foreignTenant), MEETING, SESSION, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(outbox.count()).isZero();
    }

    @Test
    void outboxClaimsAreSkipLockedAndPublicationOutcomeIsLeaseFenced() throws Exception {
        insertResolvedAssociation();
        saveSegment(TENANT, MEETING, SESSION, "SES-42", 1L,
                TranscriptSegmentStatus.FINALIZED, "final text");
        finalizationService.finalizeTranscript(context(TENANT), MEETING, SESSION, 1L);
        UUID firstToken = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimAfterBarrier(ready, start, firstToken));
            var second = executor.submit(() -> claimAfterBarrier(ready, start, secondToken));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS))
                    .isEqualTo(1);
        }

        var claimed = outbox.findAll().getFirst();
        UUID winningToken = claimed.getClaimToken();
        Integer staleOutcome = new TransactionTemplate(transactionManager).execute(status ->
                outbox.markPublishedFenced(
                        claimed.getId(), UUID.randomUUID(), Instant.now()));
        Integer winningOutcome = new TransactionTemplate(transactionManager).execute(status ->
                outbox.markPublishedFenced(claimed.getId(), winningToken, Instant.now()));
        assertThat(staleOutcome).isZero();
        assertThat(winningOutcome).isEqualTo(1);
    }

    @Test
    void crashedAssociationClaimsRemainRetryableWithoutConsumingFailureBudget() {
        UUID associationId = UUID.randomUUID();
        UUID firstToken = UUID.randomUUID();
        insertAssociation(associationId, TENANT, MEETING, "SES-crash", null,
                "RESOLVING", firstToken, 0);
        Instant firstRecoveryAt = Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("UPDATE " + SCHEMA + ".transcript_session_associations "
                        + "SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(firstRecoveryAt.minusMillis(1)), associationId);
        Instant retryAt = firstRecoveryAt.plusSeconds(5);

        assertThat(associationStore.recoverStale(
                TENANT, MEETING, "SES-crash", retryAt, firstRecoveryAt)).isTrue();
        var pending = associations.findById(associationId).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(TranscriptSessionAssociationStatus.PENDING);
        assertThat(pending.getResolutionAttempts()).isZero();
        assertThat(pending.getLastErrorCode()).isEqualTo("LEASE_EXPIRED");
        assertThat(pending.getNextRetryAt()).isEqualTo(retryAt);
        assertThat(associationStore.claim(
                TENANT, MEETING, "SES-crash", UUID.randomUUID(),
                retryAt.minusMillis(1), retryAt.plusSeconds(1))).isFalse();

        UUID secondToken = UUID.randomUUID();
        Instant secondClaimAt = retryAt.plusMillis(1);
        assertThat(associationStore.claim(
                TENANT, MEETING, "SES-crash", secondToken,
                secondClaimAt, secondClaimAt.plusMillis(10))).isTrue();
        Instant secondRecoveryAt = secondClaimAt.plusMillis(20);
        assertThat(associationStore.recoverStale(
                TENANT, MEETING, "SES-crash",
                secondRecoveryAt.plusSeconds(5), secondRecoveryAt)).isTrue();

        var stillPending = associations.findById(associationId).orElseThrow();
        assertThat(stillPending.getStatus()).isEqualTo(TranscriptSessionAssociationStatus.PENDING);
        assertThat(stillPending.getResolutionAttempts()).isZero();
        assertThat(stillPending.getLastErrorCode()).isEqualTo("LEASE_EXPIRED");
        Integer lateSuccess = new TransactionTemplate(transactionManager).execute(status ->
                associations.markResolvedFenced(
                        associationId, secondToken, SESSION, secondRecoveryAt.plusMillis(1)));
        assertThat(lateSuccess).isZero();
    }

    @Test
    void publishThenCrashLeaseRecoveryIsBackedOffRetryableAndFenced() {
        insertResolvedAssociation();
        saveSegment(TENANT, MEETING, SESSION, "SES-42", 1L,
                TranscriptSegmentStatus.FINALIZED, "final text");
        finalizationService.finalizeTranscript(context(TENANT), MEETING, SESSION, 1L);
        Instant firstClaimAt = Instant.now().plusMillis(10)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID firstToken = UUID.randomUUID();
        Integer firstClaim = new TransactionTemplate(transactionManager).execute(status ->
                outbox.claimBatch(firstClaimAt, firstClaimAt.plusMillis(10),
                        "worker-a", firstToken, 1));
        assertThat(firstClaim).isEqualTo(1);

        Instant firstRecoveryAt = firstClaimAt.plusMillis(20);
        Instant retryAt = firstRecoveryAt.plusSeconds(5);
        Integer firstRecovery = new TransactionTemplate(transactionManager).execute(status ->
                outbox.recoverStaleLeases(firstRecoveryAt, retryAt));
        assertThat(firstRecovery).isEqualTo(1);
        var pending = outbox.findAll().getFirst();
        assertThat(pending.getStatus())
                .isEqualTo(com.example.transcript.model.TranscriptEventOutboxStatus.PENDING);
        assertThat(pending.getAttempts()).isZero();
        assertThat(pending.getLastError()).isEqualTo("LEASE_EXPIRED");
        assertThat(pending.getNextAttemptAt()).isEqualTo(retryAt);
        Integer tooEarly = new TransactionTemplate(transactionManager).execute(status ->
                outbox.claimBatch(retryAt.minusMillis(1), retryAt.plusSeconds(1),
                        "too-early", UUID.randomUUID(), 1));
        assertThat(tooEarly).isZero();

        UUID secondToken = UUID.randomUUID();
        Instant secondClaimAt = retryAt.plusMillis(1);
        Integer secondClaim = new TransactionTemplate(transactionManager).execute(status ->
                outbox.claimBatch(secondClaimAt, secondClaimAt.plusMillis(10),
                        "worker-b", secondToken, 1));
        assertThat(secondClaim).isEqualTo(1);
        Instant secondRecoveryAt = secondClaimAt.plusMillis(20);
        Integer secondRecovery = new TransactionTemplate(transactionManager).execute(status ->
                outbox.recoverStaleLeases(
                        secondRecoveryAt, secondRecoveryAt.plusSeconds(5)));
        assertThat(secondRecovery).isEqualTo(1);

        var stillPending = outbox.findAll().getFirst();
        assertThat(stillPending.getStatus())
                .isEqualTo(com.example.transcript.model.TranscriptEventOutboxStatus.PENDING);
        assertThat(stillPending.getAttempts()).isZero();
        assertThat(stillPending.getLastError()).isEqualTo("LEASE_EXPIRED");
        Integer latePublish = new TransactionTemplate(transactionManager).execute(status ->
                outbox.markPublishedFenced(
                        stillPending.getId(), secondToken, secondRecoveryAt.plusMillis(1)));
        assertThat(latePublish).isZero();
    }

    private com.example.transcript.dto.TranscriptFinalizationDto finalizeAfterBarrier(
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("barrier timeout");
        }
        return finalizationService.finalizeTranscript(context(TENANT), MEETING, SESSION, 1L);
    }

    private int claimAfterBarrier(CountDownLatch ready, CountDownLatch start, UUID token)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("barrier timeout");
        }
        Integer claimed = new TransactionTemplate(transactionManager).execute(status ->
                outbox.claimBatch(Instant.now(), Instant.now().plusSeconds(30),
                        "test", token, 1));
        return claimed == null ? 0 : claimed;
    }

    private void insertResolvedAssociation() {
        insertAssociation(UUID.randomUUID(), TENANT, MEETING, "SES-42", SESSION,
                "RESOLVED", null, 0);
    }

    private UUID insertQuiescingAssociation(boolean deadlineExpired) {
        UUID associationId = UUID.randomUUID();
        insertAssociation(associationId, TENANT, MEETING, "SES-quiescent", SESSION,
                "RESOLVED", null, 0);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant maxWaitAt = deadlineExpired ? now.minusSeconds(1) : now.plusSeconds(60);
        jdbc.update("UPDATE " + SCHEMA + ".transcript_session_associations SET "
                        + "finalization_state='QUIESCING', finalization_cycle_version=1, "
                        + "recording_finished_at=?, finish_observed_at=?, last_content_changed_at=?, "
                        + "min_wait_at=?, quiescence_due_at=?, max_wait_at=? WHERE id=?",
                Timestamp.from(now.minusSeconds(600)), Timestamp.from(now.minusSeconds(590)),
                Timestamp.from(now.minusSeconds(120)), Timestamp.from(now.minusSeconds(30)),
                Timestamp.from(now.minusSeconds(1)), Timestamp.from(maxWaitAt), associationId);
        return associationId;
    }

    private void insertAssociation(
            UUID id,
            UUID tenant,
            UUID meeting,
            String sourceSession,
            UUID session,
            String status,
            UUID claimToken,
            long finalizationVersion) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO " + SCHEMA + ".transcript_session_associations "
                        + "(id,tenant_id,org_id,meeting_id,source_system,source_session_id,session_id,"
                        + "status,resolution_attempts,claim_token,lease_expires_at,finalization_version,"
                        + "created_at,updated_at,version) VALUES (?,?,?,?,?,?,?,?,0,?,?,?, ?,?,0)",
                id, tenant, tenant, meeting, DirectSttTranscriptResultEvent.SOURCE_SYSTEM,
                sourceSession, session, status, claimToken,
                claimToken == null ? null : Timestamp.from(now.plusSeconds(30)),
                finalizationVersion, Timestamp.from(now), Timestamp.from(now));
    }

    private TranscriptSegment saveSegment(
            UUID tenant,
            UUID meeting,
            UUID session,
            String sourceSession,
            long chunk,
            TranscriptSegmentStatus status,
            String finalText) {
        TranscriptSegment row = new TranscriptSegment();
        row.setTenantId(tenant);
        row.setOrgId(tenant);
        row.setMeetingId(meeting);
        row.setSessionId(session);
        row.setStartTime((double) chunk);
        row.setEndTime(chunk + 1.0d);
        row.setTextDraft(status == TranscriptSegmentStatus.REDACTED ? null : "draft");
        row.setTextFinal(finalText);
        row.setStatus(status);
        row.setSourceSystem(DirectSttTranscriptResultEvent.SOURCE_SYSTEM);
        row.setSourceSessionId(sourceSession);
        row.setSourceChunkSeq(chunk);
        return segments.saveAndFlush(row);
    }

    private void insertOutboxCollision(String eventKey) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO " + SCHEMA + ".transcript_event_outbox "
                        + "(id,event_type,aggregate_id,meeting_id,tenant_id,org_id,payload,event_key,"
                        + "status,attempts,created_at,updated_at,version) "
                        + "VALUES (?,?,?,?,?,?,?,?,'PENDING',0,?,?,0)",
                UUID.randomUUID(), "meeting.transcript.ready", SESSION, MEETING, TENANT, TENANT,
                "{}", eventKey, Timestamp.from(now), Timestamp.from(now));
    }

    private AdminTenantContext context(UUID tenant) {
        return new AdminTenantContext(tenant, "admin");
    }
}
