package com.example.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.transcript.directstt.DirectSttTranscriptResultEvent;
import com.example.transcript.directstt.TranscriptSessionAssociationStore;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.security.AdminTenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
@Import({TranscriptFinalizationService.class, TranscriptSessionAssociationStore.class})
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
    @Autowired private TranscriptSessionAssociationStore associationStore;
    @Autowired private TranscriptSessionAssociationRepository associations;
    @Autowired private TranscriptSegmentRepository segments;
    @Autowired private TranscriptFinalizationRepository finalizations;
    @Autowired private TranscriptEventOutboxRepository outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM " + SCHEMA + ".transcript_event_outbox");
        jdbc.update("DELETE FROM " + SCHEMA + ".transcript_finalizations");
        jdbc.update("DELETE FROM " + SCHEMA + ".transcript_segments");
        jdbc.update("DELETE FROM " + SCHEMA + ".transcript_session_associations");
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
    void duplicateFinalizationHasOneImmutableOccurrenceAndOneThinOutboxRow() {
        insertResolvedAssociation();
        saveSegment(TENANT, MEETING, SESSION, "SES-42", 1L,
                TranscriptSegmentStatus.FINALIZED, "private final transcript");

        var first = finalizationService.finalizeTranscript(context(TENANT), MEETING, SESSION, 1L);
        var duplicate = finalizationService.finalizeTranscript(context(TENANT), MEETING, SESSION, 1L);

        assertThat(duplicate).isEqualTo(first);
        assertThat(finalizations.count()).isEqualTo(1L);
        assertThat(outbox.count()).isEqualTo(1L);
        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM " + SCHEMA + ".transcript_event_outbox",
                String.class);
        assertThat(payload)
                .contains("meeting.transcript.ready", SESSION.toString())
                .doesNotContain("private final transcript", "textDraft", "textFinal", "audio");
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
    void crashedAssociationClaimsConsumeAttemptsAndEndDead() {
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
                TENANT, MEETING, "SES-crash", 2, retryAt, firstRecoveryAt)).isTrue();
        var pending = associations.findById(associationId).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(TranscriptSessionAssociationStatus.PENDING);
        assertThat(pending.getResolutionAttempts()).isEqualTo(1);
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
                TENANT, MEETING, "SES-crash", 2,
                secondRecoveryAt.plusSeconds(5), secondRecoveryAt)).isTrue();

        var dead = associations.findById(associationId).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(TranscriptSessionAssociationStatus.DEAD);
        assertThat(dead.getResolutionAttempts()).isEqualTo(2);
        assertThat(dead.getLastErrorCode()).isEqualTo("LEASE_EXPIRED");
        Integer lateSuccess = new TransactionTemplate(transactionManager).execute(status ->
                associations.markResolvedFenced(
                        associationId, secondToken, SESSION, secondRecoveryAt.plusMillis(1)));
        assertThat(lateSuccess).isZero();
    }

    @Test
    void publishThenCrashLeaseRecoveryIsBackedOffBoundedAndFenced() {
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
                outbox.recoverStaleLeases(firstRecoveryAt, retryAt, 2));
        assertThat(firstRecovery).isEqualTo(1);
        var pending = outbox.findAll().getFirst();
        assertThat(pending.getStatus())
                .isEqualTo(com.example.transcript.model.TranscriptEventOutboxStatus.PENDING);
        assertThat(pending.getAttempts()).isEqualTo(1);
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
                        secondRecoveryAt, secondRecoveryAt.plusSeconds(5), 2));
        assertThat(secondRecovery).isEqualTo(1);

        var dead = outbox.findAll().getFirst();
        assertThat(dead.getStatus())
                .isEqualTo(com.example.transcript.model.TranscriptEventOutboxStatus.DEAD);
        assertThat(dead.getAttempts()).isEqualTo(2);
        assertThat(dead.getLastError()).isEqualTo("LEASE_EXPIRED");
        Integer latePublish = new TransactionTemplate(transactionManager).execute(status ->
                outbox.markPublishedFenced(
                        dead.getId(), secondToken, secondRecoveryAt.plusMillis(1)));
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
                        + "VALUES (?,?,?,?,?,?,?::jsonb,?,'PENDING',0,?,?,0)",
                UUID.randomUUID(), "meeting.transcript.ready", SESSION, MEETING, TENANT, TENANT,
                "{}", eventKey, Timestamp.from(now), Timestamp.from(now));
    }

    private AdminTenantContext context(UUID tenant) {
        return new AdminTenantContext(tenant, "admin");
    }
}
