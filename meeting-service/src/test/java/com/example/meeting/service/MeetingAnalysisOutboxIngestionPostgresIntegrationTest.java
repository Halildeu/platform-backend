package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.meeting.dto.v1.internal.MeetingAnalysisActionIngest;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestRequest;
import com.example.meeting.model.Meeting;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.security.AnalysisJobCapabilityVerifier;
import com.example.meeting.support.AnalysisJobCapabilityTestTokens;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-1d transactional-outbox WRITE behaviour on a real Postgres engine — Faz 24
 * (platform-ai#244). Same Boot slice as the BE-1c ingestion test (no test
 * transaction — each service call commits for real), so these prove the outbox
 * rows land in the SAME atomic unit as the run + children:
 *
 * <ul>
 *   <li>a committed ingestion writes exactly one {@code meeting.summary.ready} plus
 *       one {@code meeting.action.assigned} per non-null-assignee action;</li>
 *   <li>a NULL/blank-assignee action writes NO event (LLM attribution guard);</li>
 *   <li>a retried ingestion does not duplicate outbox rows (event_key exactly-once);</li>
 *   <li>a mid-write child violation rolls the outbox rows back too (atomicity).</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(
        classes = MeetingAnalysisOutboxIngestionPostgresIntegrationTest.Boot.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MeetingAnalysisOutboxIngestionPostgresIntegrationTest {

    private static final String SCHEMA = "meeting_service";
    private static final String SHA_A = "a".repeat(64);
    private static final Instant GEN = Instant.parse("2026-07-11T10:00:00Z");
    private static final Instant FINALIZED = Instant.parse("2026-07-11T09:59:00Z");
    private static final UUID SESSION_ID = UUID.fromString("41cced6d-b538-42ea-8178-92c3ce4157b4");

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
        registry.add(
                "security.analysis-job-capability.hmac-secret",
                () -> AnalysisJobCapabilityTestTokens.ENCODED_SECRET);
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
    @Import({
            MeetingAnalysisResultIngestionService.class,
            MeetingAnalysisResultWriter.class,
            MeetingAnalysisPayloadHasher.class,
            AnalysisGeneratedAtPolicy.class,
            AnalysisJobCapabilityVerifier.class
    })
    static class Boot {
    }

    @Autowired
    private MeetingAnalysisResultIngestionService service;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_event_outbox");
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_actions");
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_decisions");
        jdbc.update("DELETE FROM " + SCHEMA + ".meeting_analysis_runs");
        jdbc.update("DELETE FROM " + SCHEMA + ".meetings");
    }

    @Test
    void ingest_writesSummaryReadyAndActionAssignedOutboxRows_inSameCommit() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        Instant due = Instant.parse("2026-07-20T09:00:00Z");

        ingest(meetingId, runId, request(SHA_A, "Toplantı özeti",
                List.of("Karar 1", "Karar 2"),
                List.of(new MeetingAnalysisActionIngest("Raporu gönder", "ali@example.com", due),
                        new MeetingAnalysisActionIngest("Takip et", null, null))));

        List<Map<String, Object>> rows = outboxRows(runId);
        // Exactly 2 events: 1 summary.ready + 1 action.assigned (NOT for the null-assignee action).
        assertThat(rows).hasSize(2);

        Map<String, Object> summary = rows.stream()
                .filter(r -> r.get("event_type").equals("meeting.summary.ready")).findFirst().orElseThrow();
        assertThat(summary.get("event_key")).isEqualTo(runId + "|meeting.summary.ready");
        assertThat(summary.get("status")).isEqualTo("PENDING");
        assertThat(summary.get("aggregate_id")).isEqualTo(runId);
        assertThat(summary.get("meeting_id")).isEqualTo(meetingId);
        assertThat(summary.get("tenant_id")).isEqualTo(org);
        assertThat(summary.get("org_id")).isEqualTo(org);
        // Extract JSONB fields via SQL (robust to jsonb whitespace/key-order normalisation).
        String summaryKey = runId + "|meeting.summary.ready";
        assertThat(jsonField(summaryKey, "schema")).isEqualTo("meeting.event.v1");
        assertThat(jsonField(summaryKey, "decisionCount")).isEqualTo("2");
        assertThat(jsonField(summaryKey, "actionCount")).isEqualTo("2");

        Map<String, Object> action = rows.stream()
                .filter(r -> r.get("event_type").equals("meeting.action.assigned")).findFirst().orElseThrow();
        assertThat(action.get("event_key")).isEqualTo(runId + "|meeting.action.assigned|0");
        String actionKey = runId + "|meeting.action.assigned|0";
        assertThat(jsonField(actionKey, "assigneeSubject")).isEqualTo("ali@example.com");
        assertThat(jsonField(actionKey, "ordinal")).isEqualTo("0");
    }

    @Test
    void nullAndBlankAssigneeActions_writeNoActionAssignedRows_attributionGuard() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();

        ingest(meetingId, runId, request(SHA_A, "özet", List.of(),
                List.of(new MeetingAnalysisActionIngest("assigned", "bob@example.com", null),
                        new MeetingAnalysisActionIngest("nobody", null, null),
                        new MeetingAnalysisActionIngest("blank", "   ", null))));

        // 1 summary.ready + exactly 1 action.assigned (only the real assignee).
        assertThat(outboxRows(runId)).extracting(r -> r.get("event_type"))
                .containsExactlyInAnyOrder("meeting.summary.ready", "meeting.action.assigned");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
                        + ".meeting_event_outbox WHERE aggregate_id = ? AND event_type = 'meeting.action.assigned'",
                Integer.class, runId)).isEqualTo(1);
    }

    @Test
    void runWithoutSummary_writesNoSummaryReadyButStillActionAssigned() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();

        ingest(meetingId, runId, request(SHA_A, null, List.of(),
                List.of(new MeetingAnalysisActionIngest("assigned", "carol@example.com", null))));

        assertThat(outboxRows(runId)).extracting(r -> r.get("event_type"))
                .containsExactly("meeting.action.assigned");
    }

    @Test
    void retrySameKey_doesNotDuplicateOutboxRows_exactlyOnce() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        var request = request(SHA_A, "özet",
                List.of("k"), List.of(new MeetingAnalysisActionIngest("a", "dan@example.com", null)));

        ingest(meetingId, runId, request);
        ingest(meetingId, runId, request); // idempotent replay — must NOT re-emit

        // Still exactly 2 rows (1 summary.ready + 1 action.assigned) — the UNIQUE
        // event_key + the replay short-circuit make re-emission impossible.
        assertThat(outboxRows(runId)).hasSize(2);
    }

    @Test
    void childConstraintViolationMidWrite_rollsBackOutboxRowsToo() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();

        // A decision detail over VARCHAR(4000) aborts the flush — the run, children
        // AND the outbox rows must all roll back (nothing half-committed).
        String overLong = "x".repeat(4001);
        var request = request(SHA_A, "özet", List.of("ok", overLong),
                List.of(new MeetingAnalysisActionIngest("a", "erin@example.com", null)));

        assertThatThrownBy(() -> ingest(meetingId, runId, request))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(outboxCount(runId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
                + ".meeting_analysis_runs WHERE analysis_run_id = ?", Integer.class, runId)).isZero();
    }

    // ────────────────────────── helpers ──────────────────────────

    private MeetingAnalysisResultIngestRequest request(
            String sha, String summary, List<String> decisions, List<MeetingAnalysisActionIngest> actions) {
        return new MeetingAnalysisResultIngestRequest(
                null, SESSION_ID.toString(), sha, 1L, FINALIZED, "analysis-v1",
                "5-adr0043", "gpt-x", "openai", "p1",
                summary, "verified", List.of(), List.of(), List.of(),
                0, false, 0, GEN, decisions, actions, null);
    }

    private void ingest(
            UUID meetingId, UUID analysisRunId, MeetingAnalysisResultIngestRequest request) {
        UUID tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM " + SCHEMA + ".meetings WHERE id = ?",
                UUID.class,
                meetingId);
        service.ingest(
                meetingId,
                analysisRunId,
                AnalysisJobCapabilityTestTokens.issue(tenantId, meetingId, analysisRunId, request),
                request);
    }

    private UUID insertMeeting(UUID org) {
        UUID meetingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO %s.meetings
                  (id, tenant_id, org_id, title, status, organizer_subject,
                   created_by_subject, last_updated_by_subject, created_at, updated_at)
                VALUES (?, ?, ?, 'outbox test', 'SCHEDULED', 'organizer', 'creator', 'updater', ?, ?)
                """.formatted(SCHEMA), meetingId, org, org, now(), now());
        return meetingId;
    }

    private List<Map<String, Object>> outboxRows(UUID aggregateId) {
        return jdbc.queryForList("SELECT event_type, event_key, status, aggregate_id, meeting_id, "
                + "tenant_id, org_id, payload::text AS payload, attempts "
                + "FROM " + SCHEMA + ".meeting_event_outbox WHERE aggregate_id = ? ORDER BY event_key", aggregateId);
    }

    private int outboxCount(UUID aggregateId) {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA
                + ".meeting_event_outbox WHERE aggregate_id = ?", Integer.class, aggregateId);
        return c == null ? 0 : c;
    }

    /** Extract a top-level JSONB field as text — robust to jsonb whitespace/key-order. */
    private String jsonField(String eventKey, String field) {
        return jdbc.queryForObject("SELECT payload->>? FROM " + SCHEMA
                + ".meeting_event_outbox WHERE event_key = ?", String.class, field, eventKey);
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
