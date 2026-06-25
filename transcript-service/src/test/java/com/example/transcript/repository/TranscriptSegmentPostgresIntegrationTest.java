package com.example.transcript.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.transcript.model.TranscriptAccessAudit;
import com.example.transcript.model.TranscriptAccessType;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
 * Postgres Testcontainers integration test for the V1 migration's org_id compat
 * layer + the KVKK m.12 access-audit write path on a REAL Postgres engine
 * (H2 cannot reliably exercise the BEFORE INSERT/UPDATE trigger or the CHECK
 * constraint). The real {@code V1__transcript_baseline.sql} runs via Flyway.
 *
 * <p>Coverage:
 * <ol>
 *   <li>Canonical segment row (org_id = tenant_id) persists + reads back.</li>
 *   <li>V1 trigger BACK-FILLS org_id from tenant_id when a writer omits it.</li>
 *   <li>V1 CHECK REJECTS a both-set mismatch with SQLSTATE 23514 (trigger
 *       disabled so the explicit mismatch reaches the constraint).</li>
 *   <li>The {@code end_time >= start_time} CHECK rejects an inverted segment.</li>
 *   <li>An access-audit row WRITES with the canonical org_id, and the stored
 *       row carries access metadata only — NO transcript text / search term
 *       column exists on the table (TRANSCRIPT-FREE proof at the schema level).</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TranscriptSegmentPostgresIntegrationTest {

    private static final String SCHEMA = "transcript_service";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("transcript")
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
    private TranscriptSegmentRepository segmentRepository;
    @Autowired
    private TranscriptAccessAuditRepository auditRepository;
    @Autowired
    private JdbcTemplate jdbc;

    // ── 1. canonical row ──────────────────────────────────────────────

    @Test
    void canonicalSegment_persistsAndReadsBack() {
        UUID org = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        TranscriptSegment seg = new TranscriptSegment();
        seg.setTenantId(org);
        seg.setOrgId(org);
        seg.setMeetingId(meeting);
        seg.setStartTime(0.0);
        seg.setEndTime(1.25);
        seg.setTextDraft("hello canonical");
        seg.setStatus(TranscriptSegmentStatus.DRAFT);
        TranscriptSegment saved = segmentRepository.saveAndFlush(seg);

        assertThat(segmentRepository.findVisibleToOrgAndId(org, saved.getId())).isPresent();
    }

    // ── 2. trigger back-fill ──────────────────────────────────────────

    @Test
    void v1Trigger_backfillsOrgIdFromTenantId_whenWriterOmitsIt() {
        UUID tenant = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-16T10:00:00Z"));
        // Insert with org_id NULL via raw SQL — the V1 trigger must fill it.
        jdbc.update("INSERT INTO " + SCHEMA + ".transcript_segments "
                        + "(id, tenant_id, org_id, meeting_id, start_time, end_time, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, NULL, ?, 0.0, 1.0, 'DRAFT', ?, ?, 0)",
                id, tenant, UUID.randomUUID(), now, now);

        UUID filledOrgId = jdbc.queryForObject(
                "SELECT org_id FROM " + SCHEMA + ".transcript_segments WHERE id = ?",
                UUID.class, id);
        assertThat(filledOrgId).as("V1 trigger back-fills org_id = tenant_id").isEqualTo(tenant);
    }

    // ── 3. CHECK mismatch → 23514 ─────────────────────────────────────

    @Test
    void v1Check_rejectsOrgIdTenantIdMismatch_with23514() {
        UUID tenant = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-16T10:00:00Z"));
        // Disable the compat trigger so the explicit mismatch reaches the CHECK
        // (otherwise the trigger would only fill a NULL, never overwrite a set
        // value). The failed INSERT aborts the tx; @DataJpaTest rolls back +
        // re-enables the trigger.
        jdbc.execute("ALTER TABLE " + SCHEMA + ".transcript_segments DISABLE TRIGGER USER");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".transcript_segments "
                        + "(id, tenant_id, org_id, meeting_id, start_time, end_time, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 0.0, 1.0, 'DRAFT', ?, ?, 0)",
                id, tenant, otherOrg, UUID.randomUUID(), now, now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t))
                        .as("org_id != tenant_id must be 23514 check_violation")
                        .isEqualTo("23514"));
    }

    // ── 4. time-order CHECK ───────────────────────────────────────────

    @Test
    void timeOrderCheck_rejectsEndBeforeStart_with23514() {
        UUID org = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-16T10:00:00Z"));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".transcript_segments "
                        + "(id, tenant_id, org_id, meeting_id, start_time, end_time, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 9.0, 1.0, 'DRAFT', ?, ?, 0)",
                id, org, org, UUID.randomUUID(), now, now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23514"));
    }

    // ── 5. audit write + transcript-free schema ───────────────────────

    @Test
    void accessAudit_writesCanonicalRow_andTableIsTranscriptFree() {
        UUID org = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();

        TranscriptAccessAudit audit = new TranscriptAccessAudit();
        audit.setTenantId(org);
        audit.setOrgId(org);
        audit.setAccessorSubject("admin@example.com");
        audit.setAccessType(TranscriptAccessType.READ);
        audit.setSegmentId(segmentId);
        audit.setMeetingId(meeting);
        audit.setAccessedAt(Instant.now());
        TranscriptAccessAudit saved = auditRepository.saveAndFlush(audit);

        // The row persisted with the canonical org_id.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tenant_id, org_id, accessor_subject, access_type, segment_id, result_count "
                        + "FROM " + SCHEMA + ".transcript_access_audit WHERE id = ?",
                saved.getId());
        assertThat(row.get("org_id")).isEqualTo(org);
        assertThat(row.get("tenant_id")).isEqualTo(org);
        assertThat(row.get("access_type")).isEqualTo("READ");
        assertThat(row.get("accessor_subject")).isEqualTo("admin@example.com");

        // TRANSCRIPT-FREE proof at the SCHEMA level: the audit table has NO
        // column whose name implies it could hold transcript text or a search
        // term. If a future migration adds one, this fails the build.
        List<String> columns = jdbc.queryForList(
                "SELECT lower(column_name) FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'transcript_access_audit'",
                String.class, SCHEMA);
        assertThat(columns)
                .as("transcript_access_audit must carry NO transcript text / search-term column")
                .doesNotContain("text", "text_draft", "text_final", "transcript",
                        "query", "search_term", "term", "content");
        // And it DOES carry the metadata columns we rely on.
        assertThat(columns).contains("accessor_subject", "access_type", "segment_id",
                "meeting_id", "result_count", "accessed_at");
    }

    @Test
    void accessAudit_listType_persistsResultCount_withNullSegment() {
        UUID org = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        TranscriptAccessAudit audit = new TranscriptAccessAudit();
        audit.setTenantId(org);
        audit.setOrgId(org);
        audit.setAccessorSubject("admin@example.com");
        audit.setAccessType(TranscriptAccessType.LIST);
        audit.setMeetingId(meeting);
        audit.setResultCount(42);
        // segmentId intentionally null for LIST.
        TranscriptAccessAudit saved = auditRepository.saveAndFlush(audit);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT access_type, segment_id, result_count "
                        + "FROM " + SCHEMA + ".transcript_access_audit WHERE id = ?",
                saved.getId());
        assertThat(row.get("access_type")).isEqualTo("LIST");
        assertThat(row.get("segment_id")).isNull();
        assertThat(row.get("result_count")).isEqualTo(42);
    }

    @Test
    void directSttSourceChunkUniqueIndex_rejectsDuplicateTenantSessionChunk() {
        UUID org = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-25T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".transcript_segments "
                        + "(id, tenant_id, org_id, meeting_id, start_time, end_time, text_draft, status, "
                        + " source_system, source_session_id, source_chunk_seq, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 0.0, 1.0, 'first', 'DRAFT', "
                        + " 'DIRECT_STT', 'SES-abc', 5, ?, ?, 0)",
                UUID.randomUUID(), org, org, meeting, now, now);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + SCHEMA + ".transcript_segments "
                        + "(id, tenant_id, org_id, meeting_id, start_time, end_time, text_draft, status, "
                        + " source_system, source_session_id, source_chunk_seq, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 1.0, 2.0, 'duplicate', 'DRAFT', "
                        + " 'DIRECT_STT', 'SES-abc', 5, ?, ?, 0)",
                UUID.randomUUID(), org, org, meeting, now, now))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t))
                        .as("duplicate direct-STT source chunk must be unique_violation")
                        .isEqualTo("23505"));
    }

    private static String rootSqlState(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException sqlEx) {
                return sqlEx.getSQLState();
            }
            cur = cur.getCause();
        }
        return null;
    }
}
