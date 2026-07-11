package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.example.meeting.dto.v1.internal.MeetingAnalysisActionIngest;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestRequest;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestResponse;
import com.example.meeting.model.Meeting;
import com.example.meeting.repository.MeetingRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end atomic-ingestion behaviour on a real Postgres engine — Faz 24
 * (platform-ai#244 BE-1c). Boots ONLY the JPA/Flyway/Jackson slice plus the
 * ingestion beans (no web/security/eureka), and — unlike a {@code @DataJpaTest}
 * — runs WITHOUT an enclosing test transaction, so each service call is a real,
 * independently-committing transaction. That is a hard requirement here: the
 * atomicity, idempotency-race and cross-tenant tests all depend on genuine
 * commits and on a failed write actually rolling back.
 *
 * <p>Every test states an invariant of the ingestion contract and then proves it
 * against the real schema (V1+V2+V3): the run + children write atomically, the
 * global Idempotency-Key resolves replay vs conflict, the tenant is derived from
 * the meeting (never the payload), and two racing inserts of the same key
 * reconcile to exactly one row.
 */
@Testcontainers
@SpringBootTest(
        classes = MeetingAnalysisResultIngestionPostgresIntegrationTest.Boot.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MeetingAnalysisResultIngestionPostgresIntegrationTest {

    private static final String SCHEMA = "meeting_service";
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final Instant GEN = Instant.parse("2026-07-11T10:00:00Z");

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
    }

    /**
     * Minimal boot: the JPA/Flyway/Jackson autoconfig plus the ingestion beans
     * and the meeting repositories — no web layer, no security chain, no Eureka.
     */
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
    @Import({
            MeetingAnalysisResultIngestionService.class,
            MeetingAnalysisResultWriter.class,
            MeetingAnalysisPayloadHasher.class
    })
    static class Boot {
    }

    @Autowired
    private MeetingAnalysisResultIngestionService service;
    @Autowired
    private MeetingAnalysisPayloadHasher hasher;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    @AfterEach
    void cleanUp() {
        // Real commits accumulate; wipe the tables between tests (meeting delete
        // cascades to runs/actions/decisions, but delete children first to be explicit).
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_actions");
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_decisions");
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_analysis_runs");
        jdbc.update("DELETE FROM " + SCHEMA + ".meetings");
    }

    // ────────────────────────── Happy path + mapping ──────────────────────────

    @Test
    void ingest_persistsRunAndChildrenInOneTransaction_withDecisionAndActionMapping() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();

        String shortDecision = "Sözleşme onaylandı";
        String longDecision = "u".repeat(600); // > 512 → title truncated, detail full
        Instant due = Instant.parse("2026-07-20T09:00:00Z");
        var request = request(SHA_A, "Toplantı özeti",
                List.of(shortDecision, longDecision),
                List.of(new MeetingAnalysisActionIngest("Raporu gönder", "ali@example.com", due),
                        new MeetingAnalysisActionIngest("Takip et", null, null)),
                null);

        var response = service.ingest(meetingId, runId, request);

        assertThat(response.persisted()).isTrue();
        assertThat(response.storageMode()).isEqualTo("persisted");
        assertThat(response.idempotentReplay()).isFalse();
        assertThat(response.analysisRunId()).isEqualTo(runId);
        assertThat(response.decisionCount()).isEqualTo(2);
        assertThat(response.actionCount()).isEqualTo(2);

        assertThat(runCount(meetingId)).isEqualTo(1);

        // decisions: title = first 512 code points, detail = full text, decided_by NULL,
        // decided_at = generated_at, source = AI_ANALYSIS, ordinal 0..1.
        List<Map<String, Object>> decisions = jdbc.queryForList(
                "SELECT title, detail, decided_by_subject, decided_at, source, ordinal, created_by_subject "
                        + "FROM " + SCHEMA + ".meeting_decisions WHERE meeting_id = ? ORDER BY ordinal", meetingId);
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).get("title")).isEqualTo(shortDecision);
        assertThat(decisions.get(0).get("detail")).isEqualTo(shortDecision);
        assertThat(decisions.get(0).get("decided_by_subject")).isNull();
        assertThat(((Timestamp) decisions.get(0).get("decided_at")).toInstant()).isEqualTo(GEN);
        assertThat(decisions.get(0).get("source")).isEqualTo("AI_ANALYSIS");
        assertThat(decisions.get(0).get("ordinal")).isEqualTo(0);
        assertThat(decisions.get(0).get("created_by_subject")).isEqualTo("system:meeting-ai");
        assertThat((String) decisions.get(1).get("title")).hasSize(512);
        assertThat((String) decisions.get(1).get("detail")).hasSize(600);
        assertThat(decisions.get(1).get("ordinal")).isEqualTo(1);

        // actions: description = text, assignee_subject = assignee, due_at = due,
        // source = AI_ANALYSIS, ordinal 0..1.
        List<Map<String, Object>> actions = jdbc.queryForList(
                "SELECT description, assignee_subject, due_at, source, ordinal, status "
                        + "FROM " + SCHEMA + ".meeting_actions WHERE meeting_id = ? ORDER BY ordinal", meetingId);
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).get("description")).isEqualTo("Raporu gönder");
        assertThat(actions.get(0).get("assignee_subject")).isEqualTo("ali@example.com");
        assertThat(((Timestamp) actions.get(0).get("due_at")).toInstant()).isEqualTo(due);
        assertThat(actions.get(0).get("source")).isEqualTo("AI_ANALYSIS");
        assertThat(actions.get(0).get("ordinal")).isEqualTo(0);
        assertThat(actions.get(1).get("assignee_subject")).isNull();
        assertThat(actions.get(1).get("due_at")).isNull();
    }

    @Test
    void tenantAndOrg_areDerivedFromTheMeeting_notThePayload() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();

        service.ingest(meetingId, runId, request(SHA_A, "özet", List.of("k"),
                List.of(new MeetingAnalysisActionIngest("a", null, null)), null));

        // The request DTO has no tenant/org field at all — the only tenant authority is
        // the meeting row, and every persisted row carries it.
        Map<String, Object> run = jdbc.queryForMap("SELECT tenant_id, org_id FROM " + SCHEMA
                + ".meeting_analysis_runs WHERE analysis_run_id = ?", runId);
        assertThat(run.get("tenant_id")).isEqualTo(org);
        assertThat(run.get("org_id")).isEqualTo(org);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM " + SCHEMA
                + ".meeting_decisions WHERE meeting_id = ?", UUID.class, meetingId)).isEqualTo(org);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM " + SCHEMA
                + ".meeting_actions WHERE meeting_id = ?", UUID.class, meetingId)).isEqualTo(org);
    }

    @Test
    void unknownMeeting_is404() {
        assertThatThrownBy(() -> service.ingest(UUID.randomUUID(), UUID.randomUUID(),
                request(SHA_A, "özet", List.of(), List.of(), null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void bodyMeetingIdMismatch_is400() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        var mismatched = new MeetingAnalysisResultIngestRequest(
                UUID.randomUUID(), "SES-1", SHA_A, "5-adr0043", null, null, null,
                "özet", null, List.of(), List.of(), List.of(), 0, false, 0, GEN,
                List.of(), List.of(), null);
        assertThatThrownBy(() -> service.ingest(meetingId, UUID.randomUUID(), mismatched))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(400));
    }

    // ────────────────────────── Idempotency & conflict ──────────────────────────

    @Test
    void retrySameKeySamePayload_is200Replay_withNoDuplicateChildren() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        var request = request(SHA_A, "özet", List.of("k1", "k2"),
                List.of(new MeetingAnalysisActionIngest("a1", null, null)), null);

        var first = service.ingest(meetingId, runId, request);
        var retry = service.ingest(meetingId, runId, request);

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(retry.idempotentReplay()).isTrue();
        assertThat(retry.analysisRunId()).isEqualTo(runId);
        assertThat(runCount(meetingId)).isEqualTo(1);
        assertThat(decisionCount(meetingId)).isEqualTo(2); // not doubled
        assertThat(actionCount(meetingId)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentPayload_is409Conflict_winnerUnchanged() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();

        service.ingest(meetingId, runId, request(SHA_A, "orijinal", List.of("k"), List.of(), null));

        assertThatThrownBy(() -> service.ingest(meetingId, runId,
                request(SHA_A, "DEĞİŞTİRİLMİŞ", List.of("k"), List.of(), null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    assertThat(ex.getReason()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });

        assertThat(runCount(meetingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT summary FROM " + SCHEMA
                + ".meeting_analysis_runs WHERE analysis_run_id = ?", String.class, runId))
                .isEqualTo("orijinal");
    }

    @Test
    void sameGlobalKeyReusedForAnotherTenant_isGeneric409_not500_andWritesNothing() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID meetingA = insertMeeting(orgA);
        UUID meetingB = insertMeeting(orgB);
        UUID key = UUID.randomUUID();

        service.ingest(meetingA, key, request(SHA_A, "özet-A", List.of("k"), List.of(), null));

        // analysis_run_id is a GLOBAL pk; reusing it for tenant B must be a deterministic,
        // non-disclosing 409 — never a 500 from a raw PK collision.
        assertThatThrownBy(() -> service.ingest(meetingB, key,
                request(SHA_B, "özet-B", List.of("k"), List.of(), null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    assertThat(ex.getReason()).isEqualTo("IDEMPOTENCY_CONFLICT");
                });
        assertThat(runCount(meetingB)).isZero();
    }

    // ────────────────────────── Supersession ──────────────────────────

    @Test
    void supersession_linksToPriorRun_appendOnly() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        service.ingest(meetingId, first, request(SHA_A, "v1", List.of("k"), List.of(), null));
        var resp = service.ingest(meetingId, second, request(SHA_B, "v2", List.of("k"), List.of(), first));

        assertThat(resp.supersedesAnalysisRunId()).isEqualTo(first);
        assertThat(runCount(meetingId)).isEqualTo(2); // append-only, first survives
        assertThat(jdbc.queryForObject("SELECT supersedes_analysis_run_id FROM " + SCHEMA
                + ".meeting_analysis_runs WHERE analysis_run_id = ?", UUID.class, second)).isEqualTo(first);
    }

    @Test
    void supersedesUnknownRun_is422_andWritesNothing() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();

        assertThatThrownBy(() -> service.ingest(meetingId, runId,
                request(SHA_A, "özet", List.of(), List.of(), UUID.randomUUID())))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(422);
                    assertThat(ex.getReason()).isEqualTo("SUPERSEDES_RUN_NOT_FOUND");
                });
        assertThat(runCount(meetingId)).isZero();
    }

    @Test
    void supersedesRunInAnotherMeeting_is422() {
        UUID org = UUID.randomUUID();
        UUID meeting1 = insertMeeting(org);
        UUID meeting2 = insertMeeting(org);
        UUID runIn1 = UUID.randomUUID();
        service.ingest(meeting1, runIn1, request(SHA_A, "özet", List.of(), List.of(), null));

        // Same tenant, different meeting: supersession is a within-meeting relationship.
        assertThatThrownBy(() -> service.ingest(meeting2, UUID.randomUUID(),
                request(SHA_B, "özet", List.of(), List.of(), runIn1)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(422));
    }

    @Test
    void supersedesSelf_is422() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();

        assertThatThrownBy(() -> service.ingest(meetingId, runId,
                request(SHA_A, "özet", List.of(), List.of(), runId)))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(422);
                    assertThat(ex.getReason()).isEqualTo("SUPERSEDES_RUN_INVALID");
                });
    }

    // ────────────────────────── Atomicity ──────────────────────────

    @Test
    void childConstraintViolationMidWrite_rollsBackTheEntireAggregate() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();

        // Second decision's detail exceeds VARCHAR(4000): a REAL mid-write child
        // constraint violation, triggered after the run row is flushed.
        String overLong = "x".repeat(4001);
        var request = request(SHA_A, "özet", List.of("geçerli karar", overLong),
                List.of(new MeetingAnalysisActionIngest("a", null, null)), null);

        // Not misclassified as a race replay: no run is committed, so the original
        // persistence failure is preserved.
        assertThatThrownBy(() -> service.ingest(meetingId, runId, request))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(runCount(meetingId)).isZero();
        assertThat(decisionCount(meetingId)).isZero();
        assertThat(actionCount(meetingId)).isZero();
    }

    // ────────────────────────── Concurrency ──────────────────────────

    @Test
    void concurrentSameKeySamePayload_yieldsExactlyOneRun_oneCreated_oneReplay() throws Exception {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        var request = request(SHA_A, "özet", List.of("k1", "k2"),
                List.of(new MeetingAnalysisActionIngest("a1", null, null)), null);

        List<Outcome> outcomes = runConcurrently(
                () -> classify(() -> service.ingest(meetingId, runId, request)),
                () -> classify(() -> service.ingest(meetingId, runId, request)));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.CREATED, Outcome.REPLAY);
        assertThat(runCount(meetingId)).isEqualTo(1);
        assertThat(decisionCount(meetingId)).isEqualTo(2); // children written exactly once
        assertThat(actionCount(meetingId)).isEqualTo(1);
    }

    @Test
    void concurrentSameKeyDifferentPayload_yieldsOneCreated_oneConflict() throws Exception {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        // Identical child shape, different summary ⇒ different payload hash ⇒ conflict.
        var payloadA = request(SHA_A, "özet-A", List.of("k"),
                List.of(new MeetingAnalysisActionIngest("a", null, null)), null);
        var payloadB = request(SHA_A, "özet-B", List.of("k"),
                List.of(new MeetingAnalysisActionIngest("a", null, null)), null);

        List<Outcome> outcomes = runConcurrently(
                () -> classify(() -> service.ingest(meetingId, runId, payloadA)),
                () -> classify(() -> service.ingest(meetingId, runId, payloadB)));

        assertThat(outcomes).containsExactlyInAnyOrder(Outcome.CREATED, Outcome.CONFLICT);
        assertThat(runCount(meetingId)).isEqualTo(1);
        // Whichever won wrote its single aggregate; the loser wrote no children.
        assertThat(decisionCount(meetingId)).isEqualTo(1);
        assertThat(actionCount(meetingId)).isEqualTo(1);
    }

    @Test
    void raceRecoveryArm_sameKeySamePayload_loserReconcilesTo200Replay() throws Exception {
        assertRaceRecoveryArm(true);
    }

    @Test
    void raceRecoveryArm_sameKeyDifferentPayload_loserReconcilesTo409() throws Exception {
        assertRaceRecoveryArm(false);
    }

    /**
     * Deterministically exercises the post-failure reconciliation ARM: a seed
     * thread holds an UNCOMMITTED transaction that inserted the run row (a complete
     * run-only aggregate — the payload has empty children), so the main path
     * observes "absent" on its pre-check, then BLOCKS on the primary-key insert;
     * releasing the seed commits the winner and the main path fails with a unique
     * violation and reconciles.
     */
    private void assertRaceRecoveryArm(boolean samePayload) throws Exception {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        var request = request(SHA_A, "özet", List.of(), List.of(), null); // empty children ⇒ run row alone is complete
        String mainHash = hasher.hash(meetingId, org, runId, request);
        String seedHash = samePayload ? mainHash : "f".repeat(64);

        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            Future<?> seed = exec.submit(() -> {
                try (Connection c = dataSource.getConnection()) {
                    c.setAutoCommit(false);
                    seedRunRow(c, runId, meetingId, org, seedHash);
                    inserted.countDown();
                    release.await();
                    c.commit();
                }
                return null;
            });
            assertThat(inserted.await(15, SECONDS)).isTrue();

            Future<Object> main = exec.submit(() -> {
                try {
                    return service.ingest(meetingId, runId, request);
                } catch (ResponseStatusException e) {
                    return e;
                }
            });
            // Bias the interleaving so the main path reaches the blocking PK insert
            // BEFORE the seed commits (the assertion holds under either interleaving).
            Thread.sleep(600);
            release.countDown();

            Object result = main.get(30, SECONDS);
            seed.get(15, SECONDS);

            if (samePayload) {
                assertThat(result).isInstanceOf(MeetingAnalysisResultIngestResponse.class);
                assertThat(((MeetingAnalysisResultIngestResponse) result).idempotentReplay()).isTrue();
            } else {
                assertThat(result).isInstanceOf(ResponseStatusException.class);
                assertThat(((ResponseStatusException) result).getStatusCode().value()).isEqualTo(409);
            }
            assertThat(runCount(meetingId)).isEqualTo(1);
            assertThat(decisionCount(meetingId) + actionCount(meetingId)).isZero();
        } finally {
            exec.shutdownNow();
        }
    }

    // ────────────────────────── helpers ──────────────────────────

    private enum Outcome { CREATED, REPLAY, CONFLICT }

    private static Outcome classify(java.util.function.Supplier<MeetingAnalysisResultIngestResponse> call) {
        try {
            MeetingAnalysisResultIngestResponse r = call.get();
            return r.idempotentReplay() ? Outcome.REPLAY : Outcome.CREATED;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 409) {
                return Outcome.CONFLICT;
            }
            throw e;
        }
    }

    @SafeVarargs
    private static List<Outcome> runConcurrently(java.util.concurrent.Callable<Outcome>... tasks) throws Exception {
        int n = tasks.length;
        CyclicBarrier barrier = new CyclicBarrier(n);
        ExecutorService exec = Executors.newFixedThreadPool(n);
        try {
            List<Future<Outcome>> futures = new java.util.ArrayList<>();
            for (java.util.concurrent.Callable<Outcome> task : tasks) {
                futures.add(exec.submit(() -> {
                    barrier.await();
                    return task.call();
                }));
            }
            List<Outcome> results = new java.util.ArrayList<>();
            for (Future<Outcome> f : futures) {
                results.add(f.get(30, SECONDS));
            }
            return results;
        } finally {
            exec.shutdownNow();
        }
    }

    private MeetingAnalysisResultIngestRequest request(
            String sha, String summary, List<String> decisions,
            List<MeetingAnalysisActionIngest> actions, UUID supersedes) {
        return new MeetingAnalysisResultIngestRequest(
                null, "SES-1", sha, "5-adr0043", "gpt-x", "openai", "p1",
                summary, "verified", List.of(), List.of(), List.of(),
                0, false, 0, GEN, decisions, actions, supersedes);
    }

    private UUID insertMeeting(UUID org) {
        UUID meetingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO %s.meetings
                  (id, tenant_id, org_id, title, status, organizer_subject,
                   created_by_subject, last_updated_by_subject, created_at, updated_at)
                VALUES (?, ?, ?, 'ingestion test', 'SCHEDULED', 'organizer', 'creator', 'updater', ?, ?)
                """.formatted(SCHEMA), meetingId, org, org, now(), now());
        return meetingId;
    }

    private void seedRunRow(Connection c, UUID runId, UUID meetingId, UUID org, String payloadHash)
            throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + SCHEMA + ".meeting_analysis_runs "
                        + "(analysis_run_id, meeting_id, tenant_id, org_id, transcript_session_id, "
                        + " transcript_sha256, analyzer_contract_version, payload_hash, "
                        + " generated_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'SES-1', ?, '5-adr0043', ?, ?, ?, ?)")) {
            Timestamp now = now();
            ps.setObject(1, runId);
            ps.setObject(2, meetingId);
            ps.setObject(3, org);
            ps.setObject(4, org);
            ps.setString(5, SHA_A);
            ps.setString(6, payloadHash);
            ps.setTimestamp(7, now);
            ps.setTimestamp(8, now);
            ps.setTimestamp(9, now);
            ps.executeUpdate();
        }
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private int runCount(UUID meetingId) {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
                + ".meeting_analysis_runs WHERE meeting_id = ?", Integer.class, meetingId);
        return c == null ? 0 : c;
    }

    private int decisionCount(UUID meetingId) {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
                + ".meeting_decisions WHERE meeting_id = ?", Integer.class, meetingId);
        return c == null ? 0 : c;
    }

    private int actionCount(UUID meetingId) {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
                + ".meeting_actions WHERE meeting_id = ?", Integer.class, meetingId);
        return c == null ? 0 : c;
    }
}
