package com.example.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres Testcontainers integration test for V3 — the meeting-ai analysis-result
 * store (Faz 24, platform-ai#244 BE-1a). Runs the real Flyway migrations against a
 * genuine Postgres engine, because every invariant this file asserts is one H2
 * cannot express: partial unique indexes, composite tenant-FKs, CHECK constraints
 * and the BEFORE INSERT org_id trigger.
 *
 * <p>Each test states a claim the schema makes and then tries to violate it. A test
 * that only inserted happy-path rows would prove the columns exist, not that they
 * protect anything.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MeetingAnalysisRunPostgresIntegrationTest {

    private static final String SCHEMA = "meeting_service";
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String HASH_1 = "1".repeat(64);
    private static final String HASH_2 = "2".repeat(64);

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
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired
    private JdbcTemplate jdbc;

    // ----------------------------------------------------------------
    // Identity & idempotency
    // ----------------------------------------------------------------

    @Test
    void sameAnalysisRunId_cannotBeInsertedTwice() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        insertRun(runId, meetingId, org, SHA_A, HASH_1);

        // The retry path must collide on the primary key rather than create a second
        // canonical result for the same Idempotency-Key.
        assertThatThrownBy(() -> insertRun(runId, meetingId, org, SHA_A, HASH_1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reAnalysis_isANewRunLinkedToTheOneItSupersedes() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertRun(first, meetingId, org, SHA_A, HASH_1);
        insertRun(second, meetingId, org, SHA_B, HASH_2, first);

        // Both survive: a re-analysis never overwrites the record it replaces.
        assertThat(runCount(meetingId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT supersedes_analysis_run_id FROM " + SCHEMA
                        + ".meeting_analysis_runs WHERE analysis_run_id = ?",
                UUID.class, second))
                .isEqualTo(first);
    }

    @Test
    void aRunCannotSupersedeItself() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();

        assertThatThrownBy(() -> insertRun(runId, meetingId, org, SHA_A, HASH_1, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameTranscriptSnapshotMayBeAnalysedAgain_noUniqueKeyBlocksIt() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        // An earlier draft carried a UNIQUE(tenant, meeting, transcript_sha256,
        // analyzer_contract_version). It would have made a prompt/model change on the
        // same transcript impossible. Two runs over the same snapshot must be legal.
        insertRun(UUID.randomUUID(), meetingId, org, SHA_A, HASH_1);
        insertRun(UUID.randomUUID(), meetingId, org, SHA_A, HASH_2);

        assertThat(runCount(meetingId)).isEqualTo(2);
    }

    // ----------------------------------------------------------------
    // Tenant safety
    // ----------------------------------------------------------------

    @Test
    void trigger_backfillsOrgIdFromTenantId() {
        UUID tenant = UUID.randomUUID();
        UUID meetingId = insertMeeting(tenant);
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO %s.meeting_analysis_runs
                  (analysis_run_id, meeting_id, tenant_id, transcript_session_id,
                   transcript_sha256, analyzer_contract_version, payload_hash,
                   generated_at, created_at, updated_at)
                VALUES (?, ?, ?, 'SES-1', ?, '5-adr0043', ?, ?, ?, ?)
                """.formatted(SCHEMA),
                runId, meetingId, tenant, SHA_A, HASH_1, now(), now(), now());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tenant_id, org_id FROM " + SCHEMA
                        + ".meeting_analysis_runs WHERE analysis_run_id = ?", runId);
        assertThat(row.get("org_id")).isEqualTo(tenant);
    }

    @Test
    void orgIdMismatch_isRejected() {
        UUID tenant = UUID.randomUUID();
        UUID meetingId = insertMeeting(tenant);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO %s.meeting_analysis_runs
                  (analysis_run_id, meeting_id, tenant_id, org_id, transcript_session_id,
                   transcript_sha256, analyzer_contract_version, payload_hash,
                   generated_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SES-1', ?, '5-adr0043', ?, ?, ?, ?)
                """.formatted(SCHEMA),
                UUID.randomUUID(), meetingId, tenant, UUID.randomUUID(), SHA_A, HASH_1,
                now(), now(), now()))
                .as("the trigger back-fills a NULL org_id; it must never correct a mismatch")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aRunCannotBindToAMeetingInAnotherTenant() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID meetingInA = insertMeeting(orgA);

        assertThatThrownBy(() -> insertRun(UUID.randomUUID(), meetingInA, orgB, SHA_A, HASH_1))
                .as("composite (meeting_id, tenant_id) FK is the cross-tenant drift guard")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anAiChildCannotBindToARunInAnotherTenant() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID meetingInA = insertMeeting(orgA);
        UUID meetingInB = insertMeeting(orgB);
        UUID runInA = UUID.randomUUID();
        insertRun(runInA, meetingInA, orgA, SHA_A, HASH_1);

        assertThatThrownBy(() -> insertAiAction(meetingInB, orgB, runInA, 0))
                .as("child composite FK targets uq_analysis_runs_run_tenant")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----------------------------------------------------------------
    // Child idempotency & provenance
    // ----------------------------------------------------------------

    @Test
    void sameRunAndOrdinal_cannotBeInsertedTwice() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        insertRun(runId, meetingId, org, SHA_A, HASH_1);
        insertAiAction(meetingId, org, runId, 0);

        assertThatThrownBy(() -> insertAiAction(meetingId, org, runId, 0))
                .as("(tenant, run, ordinal) is the child idempotency key")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void identicalTextAtDifferentOrdinals_isLegal() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        insertRun(runId, meetingId, org, SHA_A, HASH_1);
        // The key is the ordinal, not the content — the same sentence may legitimately
        // appear twice in one analysis.
        insertAiAction(meetingId, org, runId, 0, "follow up with legal");
        insertAiAction(meetingId, org, runId, 1, "follow up with legal");

        assertThat(actionCount(meetingId)).isEqualTo(2);
    }

    @Test
    void manualRowsAreUnaffectedByTheChildUniqueIndex() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        // Two manual rows: analysis_run_id IS NULL, so the partial index ignores them.
        insertManualAction(meetingId, org);
        insertManualAction(meetingId, org);

        assertThat(actionCount(meetingId)).isEqualTo(2);
    }

    @Test
    void anAiRowWithoutAnOrdinal_isRejected() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        insertRun(runId, meetingId, org, SHA_A, HASH_1);

        assertThatThrownBy(() -> insertAction(meetingId, org, "AI_ANALYSIS", runId, null))
                .as("provenance CHECK makes the half-set state impossible")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aManualRowCarryingAnAnalysisRun_isRejected() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        insertRun(runId, meetingId, org, SHA_A, HASH_1);

        assertThatThrownBy(() -> insertAction(meetingId, org, "MANUAL", runId, 0))
                .as("a MANUAL row must not claim AI provenance")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existingRowsDefaultToManualProvenance() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        insertManualAction(meetingId, org);

        // V1 rows predate the column; the DEFAULT keeps them coherent with the CHECK.
        assertThat(jdbc.queryForObject(
                "SELECT source FROM " + SCHEMA + ".meeting_actions WHERE meeting_id = ?",
                String.class, meetingId))
                .isEqualTo("MANUAL");
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    @Test
    void deletingARun_cascadesToItsAiChildrenButLeavesManualOnes() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID runId = UUID.randomUUID();
        insertRun(runId, meetingId, org, SHA_A, HASH_1);
        insertAiAction(meetingId, org, runId, 0);
        insertManualAction(meetingId, org);
        assertThat(actionCount(meetingId)).isEqualTo(2);

        jdbc.update("DELETE FROM " + SCHEMA
                + ".meeting_analysis_runs WHERE analysis_run_id = ?", runId);

        assertThat(actionCount(meetingId))
                .as("the AI row cascades with its run; the human's row survives")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT source FROM " + SCHEMA + ".meeting_actions WHERE meeting_id = ?",
                String.class, meetingId))
                .isEqualTo("MANUAL");
    }

    @Test
    void deletingTheSupersededRun_leavesTheNewerOneAliveWithANullLink() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertRun(first, meetingId, org, SHA_A, HASH_1);
        insertRun(second, meetingId, org, SHA_B, HASH_2, first);

        // KVKK retention may purge the older run; the current result must not vanish.
        jdbc.update("DELETE FROM " + SCHEMA
                + ".meeting_analysis_runs WHERE analysis_run_id = ?", first);

        assertThat(runCount(meetingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT supersedes_analysis_run_id FROM " + SCHEMA
                        + ".meeting_analysis_runs WHERE analysis_run_id = ?",
                UUID.class, second))
                .as("ON DELETE SET NULL")
                .isNull();
    }

    @Test
    void deletingTheMeeting_cascadesToItsRuns() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);
        insertRun(UUID.randomUUID(), meetingId, org, SHA_A, HASH_1);

        jdbc.update("DELETE FROM " + SCHEMA + ".meetings WHERE id = ?", meetingId);

        assertThat(runCount(meetingId)).isZero();
    }

    @Test
    void aNonHexPayloadHash_isRejected() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);

        // A truncated hash, a base64 digest or an empty string must not slip past the
        // idempotency comparison by pretending to be a hash.
        assertThatThrownBy(() -> insertRun(UUID.randomUUID(), meetingId, org, SHA_A, "hash-1"))
                .as("payload_hash must be lowercase SHA-256 hex")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anUppercaseTranscriptHash_isRejected() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);

        // Case matters: "A"*64 and "a"*64 would compare unequal, so only one form is legal.
        assertThatThrownBy(() ->
                insertRun(UUID.randomUUID(), meetingId, org, "A".repeat(64), HASH_1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void negativeCounts_areRejected() {
        UUID org = UUID.randomUUID();
        UUID meetingId = insertMeeting(org);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO %s.meeting_analysis_runs
                  (analysis_run_id, meeting_id, tenant_id, org_id, transcript_session_id,
                   transcript_sha256, analyzer_contract_version, payload_hash,
                   ungrounded_count, generated_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SES-1', ?, '5-adr0043', ?, -1, ?, ?, ?)
                """.formatted(SCHEMA),
                UUID.randomUUID(), meetingId, org, org, SHA_A, HASH_1, now(), now(), now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private UUID insertMeeting(UUID org) {
        UUID meetingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO %s.meetings
                  (id, tenant_id, org_id, title, status, organizer_subject,
                   created_by_subject, last_updated_by_subject, created_at, updated_at)
                VALUES (?, ?, ?, 'analysis-run test', 'SCHEDULED', 'organizer', 'creator', 'updater', ?, ?)
                """.formatted(SCHEMA), meetingId, org, org, now(), now());
        return meetingId;
    }

    private void insertRun(UUID runId, UUID meetingId, UUID org, String sha, String payloadHash) {
        insertRun(runId, meetingId, org, sha, payloadHash, null);
    }

    private void insertRun(UUID runId, UUID meetingId, UUID org, String sha,
                           String payloadHash, UUID supersedes) {
        jdbc.update("""
                INSERT INTO %s.meeting_analysis_runs
                  (analysis_run_id, meeting_id, tenant_id, org_id, transcript_session_id,
                   transcript_sha256, analyzer_contract_version, payload_hash,
                   supersedes_analysis_run_id, generated_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SES-1', ?, '5-adr0043', ?, ?, ?, ?, ?)
                """.formatted(SCHEMA),
                runId, meetingId, org, org, sha, payloadHash, supersedes, now(), now(), now());
    }

    private void insertAiAction(UUID meetingId, UUID org, UUID runId, int ordinal) {
        insertAiAction(meetingId, org, runId, ordinal, "extracted action");
    }

    private void insertAiAction(UUID meetingId, UUID org, UUID runId, int ordinal, String text) {
        insertAction(meetingId, org, "AI_ANALYSIS", runId, ordinal, text);
    }

    private void insertManualAction(UUID meetingId, UUID org) {
        insertAction(meetingId, org, "MANUAL", null, null, "human action");
    }

    private void insertAction(UUID meetingId, UUID org, String source, UUID runId, Integer ordinal) {
        insertAction(meetingId, org, source, runId, ordinal, "action");
    }

    private void insertAction(UUID meetingId, UUID org, String source,
                              UUID runId, Integer ordinal, String text) {
        jdbc.update("""
                INSERT INTO %s.meeting_actions
                  (id, meeting_id, tenant_id, org_id, description, status, source,
                   analysis_run_id, ordinal, created_by_subject, last_updated_by_subject,
                   created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, 'creator', 'updater', ?, ?)
                """.formatted(SCHEMA),
                UUID.randomUUID(), meetingId, org, org, text, source, runId, ordinal,
                now(), now());
    }

    private int runCount(UUID meetingId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA
                        + ".meeting_analysis_runs WHERE meeting_id = ?", Integer.class, meetingId);
        return count == null ? 0 : count;
    }

    private int actionCount(UUID meetingId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM " + SCHEMA
                        + ".meeting_actions WHERE meeting_id = ?", Integer.class, meetingId);
        return count == null ? 0 : count;
    }
}
