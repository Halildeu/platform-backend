package com.example.meeting.events;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingEventOutbox;
import com.example.meeting.model.MeetingEventOutboxStatus;
import com.example.meeting.repository.MeetingEventOutboxRepository;
import com.example.meeting.repository.MeetingRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-1d outbox POLLER behaviour on a real Postgres engine — Faz 24
 * (platform-ai#244). Boots the poller + a recording publisher against the real
 * V4 schema, with the scheduler disabled so each {@link MeetingEventOutboxPoller#runCycle()}
 * is deterministic. Proves the Codex acceptance invariants:
 *
 * <ul>
 *   <li><b>commit-after-emit</b> — an UNCOMMITTED outbox row is never claimed;</li>
 *   <li><b>FOR UPDATE SKIP LOCKED</b> — two concurrent claims never take the same row;</li>
 *   <li>publish success → PUBLISHED; publish failure → retry (PENDING) then DEAD;</li>
 *   <li>stale-lease crash recovery → PENDING;</li>
 *   <li><b>exactly-once effect</b> — a re-delivered event (same event_key) applies the
 *       consumer side-effect once.</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(
        classes = MeetingEventOutboxPollerPostgresIntegrationTest.Boot.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MeetingEventOutboxPollerPostgresIntegrationTest {

    private static final String SCHEMA = "meeting_service";
    private static final String HEX = "a".repeat(64);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("meeting")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> "false");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        // Enable the poller bean, but drive it manually (no auto-tick), and shrink the
        // retry budget so the dead-letter path is reachable in two cycles.
        registry.add("meeting.events.outbox.poller.enabled", () -> "true");
        registry.add("meeting.events.outbox.scheduling-enabled", () -> "false");
        registry.add("meeting.events.outbox.max-attempts", () -> "2");
        registry.add("meeting.events.outbox.lease-duration-ms", () -> "60000");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            TransactionAutoConfiguration.class
    })
    @EntityScan(basePackageClasses = Meeting.class)
    @EnableJpaRepositories(basePackageClasses = MeetingRepository.class)
    @Import(MeetingEventOutboxPoller.class)
    static class Boot {
        @Bean
        RecordingMeetingEventPublisher recordingPublisher() {
            return new RecordingMeetingEventPublisher();
        }
    }

    @Autowired
    private MeetingEventOutboxPoller poller;
    @Autowired
    private RecordingMeetingEventPublisher publisher;
    @Autowired
    private MeetingEventOutboxRepository outboxRepo;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void resetPublisher() {
        publisher.reset();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_event_outbox");
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_analysis_runs");
        jdbc.update("DELETE FROM " + SCHEMA + ".meetings");
    }

    // ────────────────────────── happy path ──────────────────────────

    @Test
    void runCycle_publishesPendingRow_marksPublished_andClearsLease() {
        Seed seed = seed();
        UUID id = seedPending(seed, "meeting.summary.ready", seed.runId + "|meeting.summary.ready");

        poller.runCycle();

        assertThat(publisher.delivered).extracting(MeetingEventMessage::eventKey)
                .containsExactly(seed.runId + "|meeting.summary.ready");
        MeetingEventOutbox row = outboxRepo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MeetingEventOutboxStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isNotNull();
        assertThat(row.getClaimToken()).isNull();
        assertThat(row.getLeaseExpiresAt()).isNull();
    }

    // ────────────────────────── commit-after-emit (Codex #1) ──────────────────────────

    @Test
    void poller_neverClaimsUncommittedRow_thenClaimsAfterCommit() throws Exception {
        Seed seed = seed();
        UUID id = UUID.randomUUID();
        String eventKey = seed.runId + "|meeting.summary.ready";

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            insertOutboxRow(c, id, seed, "meeting.summary.ready", eventKey); // NOT committed

            // The poller runs in its own transaction on a different pooled connection:
            // the uncommitted row is outside its snapshot, so it can claim nothing.
            poller.runCycle();
            assertThat(publisher.delivered).isEmpty();

            c.commit(); // now the row is committed
        }

        poller.runCycle();
        assertThat(publisher.delivered).extracting(MeetingEventMessage::eventKey).containsExactly(eventKey);
        assertThat(outboxRepo.findById(id).orElseThrow().getStatus())
                .isEqualTo(MeetingEventOutboxStatus.PUBLISHED);
    }

    // ────────────────────────── SKIP LOCKED (Codex — no double claim) ──────────────────────────

    @Test
    void twoConcurrentClaims_partitionRows_neverClaimingTheSameRowTwice() throws Exception {
        Seed seed = seed();
        int n = 12;
        for (int i = 0; i < n; i++) {
            seedPending(seed, "meeting.action.assigned", seed.runId + "|meeting.action.assigned|" + i);
        }

        UUID tokenA = UUID.randomUUID();
        UUID tokenB = UUID.randomUUID();
        Instant now = Instant.now();
        Instant lease = now.plusSeconds(60);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = exec.submit(() -> {
                barrier.await();
                return poller.claimAtomic(now, lease, tokenA);
            });
            Future<Integer> b = exec.submit(() -> {
                barrier.await();
                return poller.claimAtomic(now, lease, tokenB);
            });
            int claimedA = a.get(30, SECONDS);
            int claimedB = b.get(30, SECONDS);

            // All rows claimed, split across the two tokens, with zero overlap.
            assertThat(claimedA + claimedB).isEqualTo(n);
            List<MeetingEventOutbox> rowsA = outboxRepo.findByClaimToken(tokenA);
            List<MeetingEventOutbox> rowsB = outboxRepo.findByClaimToken(tokenB);
            assertThat(rowsA).hasSize(claimedA);
            assertThat(rowsB).hasSize(claimedB);
            Set<UUID> idsA = ids(rowsA);
            Set<UUID> idsB = ids(rowsB);
            // No row claimed twice: the two token sets are disjoint and together cover all n.
            Set<UUID> intersection = new java.util.HashSet<>(idsA);
            intersection.retainAll(idsB);
            assertThat(intersection).isEmpty();
            assertThat(idsA.size() + idsB.size()).isEqualTo(n);
            assertThat(outboxRepo.countByStatus(MeetingEventOutboxStatus.PENDING)).isZero();
            assertThat(outboxRepo.countByStatus(MeetingEventOutboxStatus.CLAIMED)).isEqualTo(n);
        } finally {
            exec.shutdownNow();
        }
    }

    // ────────────────────────── retry → dead-letter ──────────────────────────

    @Test
    void publishFailure_retriesUntilMaxAttempts_thenDeadLetters() {
        Seed seed = seed();
        UUID id = seedPending(seed, "meeting.summary.ready", seed.runId + "|meeting.summary.ready");
        publisher.failWith(new PublishBoom());

        poller.runCycle(); // attempt 1 fails → back to PENDING
        MeetingEventOutbox afterOne = outboxRepo.findById(id).orElseThrow();
        assertThat(afterOne.getStatus()).isEqualTo(MeetingEventOutboxStatus.PENDING);
        assertThat(afterOne.getAttempts()).isEqualTo(1);
        assertThat(afterOne.getLastError()).isEqualTo("PublishBoom");
        assertThat(afterOne.getClaimToken()).isNull();

        poller.runCycle(); // attempt 2 fails → max-attempts (2) reached → DEAD
        MeetingEventOutbox afterTwo = outboxRepo.findById(id).orElseThrow();
        assertThat(afterTwo.getStatus()).isEqualTo(MeetingEventOutboxStatus.DEAD);
        assertThat(afterTwo.getAttempts()).isEqualTo(2);
    }

    @Test
    void publishFailureThenRecovery_eventuallyPublishes() {
        Seed seed = seed();
        UUID id = seedPending(seed, "meeting.summary.ready", seed.runId + "|meeting.summary.ready");

        publisher.failWith(new PublishBoom());
        poller.runCycle(); // fails → PENDING, attempts 1
        assertThat(outboxRepo.findById(id).orElseThrow().getStatus())
                .isEqualTo(MeetingEventOutboxStatus.PENDING);

        publisher.succeed();
        poller.runCycle(); // now succeeds → PUBLISHED
        assertThat(outboxRepo.findById(id).orElseThrow().getStatus())
                .isEqualTo(MeetingEventOutboxStatus.PUBLISHED);
    }

    // ────────────────────────── stale-lease recovery ──────────────────────────

    @Test
    void staleLease_isRecoveredToPending() {
        Seed seed = seed();
        UUID id = seedClaimedStale(seed, seed.runId + "|meeting.summary.ready");

        int recovered = poller.recoverStaleLeases();

        assertThat(recovered).isEqualTo(1);
        MeetingEventOutbox row = outboxRepo.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(MeetingEventOutboxStatus.PENDING);
        assertThat(row.getClaimToken()).isNull();
        assertThat(row.getLeaseExpiresAt()).isNull();
    }

    // ────────────────────────── exactly-once effect (Codex #4) ──────────────────────────

    @Test
    void redeliveryOfSameEventKey_appliesConsumerSideEffectOnlyOnce() {
        Seed seed = seed();
        UUID id = seedPending(seed, "meeting.summary.ready", seed.runId + "|meeting.summary.ready");

        poller.runCycle(); // delivered once → consumer effect applied once
        assertThat(outboxRepo.findById(id).orElseThrow().getStatus())
                .isEqualTo(MeetingEventOutboxStatus.PUBLISHED);

        // Simulate an at-least-once RE-DELIVERY (e.g. a lost ack): force the row back
        // to PENDING and poll again. The publisher redelivers the SAME event_key.
        jdbc.update("UPDATE " + SCHEMA + ".meeting_event_outbox SET status='PENDING', published_at=NULL WHERE id=?", id);
        poller.runCycle();

        // At-least-once DELIVERY (2), but the idempotent consumer applied the
        // side-effect EXACTLY ONCE (event_key de-dup).
        assertThat(publisher.delivered).hasSize(2);
        assertThat(publisher.effectCount()).isEqualTo(1);
    }

    // ────────────────────────── seeding + helpers ──────────────────────────

    private record Seed(UUID meetingId, UUID orgId, UUID runId) { }

    private Seed seed() {
        UUID org = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO %s.meetings
                  (id, tenant_id, org_id, title, status, organizer_subject,
                   created_by_subject, last_updated_by_subject, created_at, updated_at)
                VALUES (?, ?, ?, 'poller test', 'SCHEDULED', 'organizer', 'creator', 'updater', ?, ?)
                """.formatted(SCHEMA), meetingId, org, org, now(), now());
        jdbc.update("""
                INSERT INTO %s.meeting_analysis_runs
                  (analysis_run_id, meeting_id, tenant_id, org_id, transcript_session_id,
                   transcript_sha256, analyzer_contract_version, payload_hash,
                   generated_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SES-1', ?, '5-adr0043', ?, ?, ?, ?)
                """.formatted(SCHEMA), runId, meetingId, org, org, HEX, HEX, now(), now(), now());
        return new Seed(meetingId, org, runId);
    }

    private UUID seedPending(Seed seed, String eventType, String eventKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO %s.meeting_event_outbox
                  (id, event_type, aggregate_id, meeting_id, tenant_id, org_id,
                   payload, event_key, status, attempts, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 'PENDING', 0, ?, ?)
                """.formatted(SCHEMA),
                id, eventType, seed.runId, seed.meetingId, seed.orgId, seed.orgId,
                "{\"eventType\":\"" + eventType + "\"}", eventKey, now(), now());
        return id;
    }

    private UUID seedClaimedStale(Seed seed, String eventKey) {
        UUID id = UUID.randomUUID();
        Timestamp past = Timestamp.from(Instant.now().minusSeconds(120));
        jdbc.update("""
                INSERT INTO %s.meeting_event_outbox
                  (id, event_type, aggregate_id, meeting_id, tenant_id, org_id,
                   payload, event_key, status, claim_token, processing_owner,
                   claimed_at, lease_expires_at, attempts, created_at, updated_at)
                VALUES (?, 'meeting.summary.ready', ?, ?, ?, ?, CAST('{}' AS jsonb), ?, 'CLAIMED',
                        ?, 'dead-pod', ?, ?, 0, ?, ?)
                """.formatted(SCHEMA),
                id, seed.runId, seed.meetingId, seed.orgId, seed.orgId, eventKey,
                UUID.randomUUID(), past, past, now(), now());
        return id;
    }

    private void insertOutboxRow(Connection c, UUID id, Seed seed, String eventType, String eventKey)
            throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + SCHEMA + ".meeting_event_outbox "
                        + "(id, event_type, aggregate_id, meeting_id, tenant_id, org_id, payload, event_key, "
                        + " status, attempts, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 'PENDING', 0, ?, ?)")) {
            Timestamp now = now();
            ps.setObject(1, id);
            ps.setString(2, eventType);
            ps.setObject(3, seed.runId);
            ps.setObject(4, seed.meetingId);
            ps.setObject(5, seed.orgId);
            ps.setObject(6, seed.orgId);
            ps.setString(7, "{}");
            ps.setString(8, eventKey);
            ps.setTimestamp(9, now);
            ps.setTimestamp(10, now);
            ps.executeUpdate();
        }
    }

    private static Set<UUID> ids(List<MeetingEventOutbox> rows) {
        return rows.stream().map(MeetingEventOutbox::getId).collect(java.util.stream.Collectors.toSet());
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    // ────────────────────────── test publisher (idempotent consumer) ──────────────────────────

    /** A publish failure the poller must treat as retryable; its simple name lands in last_error. */
    static final class PublishBoom extends RuntimeException {
        PublishBoom() {
            super("simulated publish failure");
        }
    }

    /**
     * Records every delivery AND models an idempotent downstream consumer: the
     * side-effect is applied at most once per {@code eventKey}, so a re-delivered
     * event does not double-apply (exactly-once effect).
     */
    static final class RecordingMeetingEventPublisher implements MeetingEventPublisher {
        final List<MeetingEventMessage> delivered = new CopyOnWriteArrayList<>();
        private final Set<String> consumerApplied = ConcurrentHashMap.newKeySet();
        private final AtomicInteger effects = new AtomicInteger();
        private volatile RuntimeException failure;

        @Override
        public void publish(MeetingEventMessage event) {
            if (failure != null) {
                throw failure;
            }
            delivered.add(event);
            if (consumerApplied.add(event.eventKey())) {
                effects.incrementAndGet();
            }
        }

        void failWith(RuntimeException e) {
            this.failure = e;
        }

        void succeed() {
            this.failure = null;
        }

        int effectCount() {
            return effects.get();
        }

        void reset() {
            delivered.clear();
            consumerApplied.clear();
            effects.set(0);
            failure = null;
        }
    }
}
