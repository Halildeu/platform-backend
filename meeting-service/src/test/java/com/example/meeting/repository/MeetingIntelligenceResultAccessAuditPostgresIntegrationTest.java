package com.example.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceResultResponse;
import com.example.meeting.model.MeetingIntelligenceResultAccessAudit;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.service.MeetingIntelligenceResultAccessAuditService;
import com.example.meeting.service.MeetingIntelligenceResultService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MeetingIntelligenceResultAccessAuditPostgresIntegrationTest {

    private static final String SCHEMA = "meeting_service";

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

    @Autowired
    private MeetingRepository meetingRepository;

    @Autowired
    private MeetingAnalysisRunRepository runRepository;

    @Autowired
    private MeetingDecisionRepository decisionRepository;

    @Autowired
    private MeetingActionRepository actionRepository;

    @Autowired
    private MeetingIntelligenceResultAccessAuditRepository accessAuditRepository;

    @Test
    void migrationExposesOnlyMetadataColumnsAndRetentionIndex() {
        List<String> columns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = 'meeting_intelligence_result_access_audit'
                ORDER BY ordinal_position
                """, String.class, SCHEMA);

        assertThat(columns).containsExactly(
                "id", "tenant_id", "org_id", "accessor_subject", "meeting_id",
                "analysis_run_id", "access_type", "result_count", "trace_id", "accessed_at");
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_indexes
                WHERE schemaname = ? AND indexname = 'idx_meeting_result_access_retention'
                """, Integer.class, SCHEMA)).isEqualTo(1);
    }

    @Test
    void schemaRejectsTenantMismatch() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> insertAudit(org, UUID.randomUUID(), 1, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaRejectsInvalidResultCount() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> insertAudit(org, org, 2, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void schemaRejectsMalformedTraceId() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> insertAudit(org, org, 1, "not-a-trace"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void destructionLedgerAcceptsSeparateResultAccessLayerCount() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO %s.meeting_retention_destruction_audit
                    (id, layer_id, cutoff_at, deleted_count, action_deleted_count,
                     decision_deleted_count, result_access_audit_deleted_count,
                     job_id, audit_payload, executed_at)
                VALUES (?, 'db.meeting-intelligence-result-access-audit', ?, 3, 0, 0, 3,
                        'result-access-retention', 'metadata-only', ?)
                """.formatted(SCHEMA), id, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

        assertThat(jdbc.queryForObject(
                "SELECT deleted_count FROM " + SCHEMA
                        + ".meeting_retention_destruction_audit WHERE id = ?",
                Long.class, id)).isEqualTo(3L);
    }

    @Test
    void successfulLegacyVisibleReadAuditsResolvedContextWithoutContent() {
        UUID tenantId = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-12T18:00:00Z");

        insertLegacyMeetingAndRun(tenantId, meetingId, runId, now);

        MeetingIntelligenceResultService service = new MeetingIntelligenceResultService(
                meetingRepository,
                runRepository,
                decisionRepository,
                actionRepository,
                new MeetingIntelligenceResultAccessAuditService(accessAuditRepository),
                new ObjectMapper());
        AdminTenantContext context =
                new AdminTenantContext(tenantId, "reader@example.com", "role-admin");

        MeetingIntelligenceResultResponse response = service.getLatest(context, meetingId);

        assertThat(response.analysisRunId()).isEqualTo(runId);
        assertThat(response.summary()).isEqualTo("legacy-visible-content");
        assertThat(accessAuditRepository.findByTenantIdOrderByAccessedAtDesc(tenantId))
                .singleElement()
                .satisfies(audit -> assertResolvedContextAudit(audit, tenantId, meetingId, runId));
    }

    private void insertAudit(UUID tenantId, UUID orgId, int resultCount, String traceId) {
        jdbc.update("""
                INSERT INTO %s.meeting_intelligence_result_access_audit
                    (id, tenant_id, org_id, accessor_subject, meeting_id, analysis_run_id,
                     access_type, result_count, trace_id, accessed_at)
                VALUES (?, ?, ?, 'reader', ?, ?, 'CANONICAL_RESULT_READ', ?, ?, ?)
                """.formatted(SCHEMA),
                UUID.randomUUID(), tenantId, orgId, UUID.randomUUID(), UUID.randomUUID(),
                resultCount, traceId, Timestamp.from(Instant.now()));
    }

    private void insertLegacyMeetingAndRun(
            UUID tenantId, UUID meetingId, UUID runId, Instant now) {
        jdbc.execute("ALTER TABLE " + SCHEMA
                + ".meetings DISABLE TRIGGER meetings_org_id_compat");
        jdbc.execute("ALTER TABLE " + SCHEMA
                + ".meeting_analysis_runs DISABLE TRIGGER meeting_analysis_runs_org_id_compat");
        try {
            jdbc.update("""
                    INSERT INTO %s.meetings
                        (id, tenant_id, org_id, title, status, organizer_subject,
                         created_by_subject, last_updated_by_subject, created_at, updated_at, version)
                    VALUES (?, ?, NULL, 'Legacy meeting', 'SCHEDULED', 'owner',
                            'owner', 'owner', ?, ?, 0)
                    """.formatted(SCHEMA), meetingId, tenantId,
                    Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO %s.meeting_analysis_runs
                        (analysis_run_id, meeting_id, tenant_id, org_id,
                         transcript_session_id, transcript_sha256,
                         analyzer_contract_version, model, backend, prompt_version,
                         payload_hash, summary, summary_grounding_status,
                         summary_citations, citations, rejected_claims,
                         ungrounded_count, redacted, redaction_count,
                         generated_at, created_at, updated_at, version)
                    VALUES (?, ?, ?, NULL, 'SES-legacy', ?, '5-adr0043',
                            'model', 'backend', 'prompt', ?, 'legacy-visible-content',
                            'verified', '[]'::jsonb, '[]'::jsonb, '[]'::jsonb,
                            0, FALSE, 0, ?, ?, ?, 0)
                    """.formatted(SCHEMA), runId, meetingId, tenantId,
                    "a".repeat(64), "b".repeat(64),
                    Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        } finally {
            jdbc.execute("ALTER TABLE " + SCHEMA
                    + ".meeting_analysis_runs ENABLE TRIGGER meeting_analysis_runs_org_id_compat");
            jdbc.execute("ALTER TABLE " + SCHEMA
                    + ".meetings ENABLE TRIGGER meetings_org_id_compat");
        }
    }

    private static void assertResolvedContextAudit(
            MeetingIntelligenceResultAccessAudit audit,
            UUID tenantId,
            UUID meetingId,
            UUID runId) {
        assertThat(audit.getTenantId()).isEqualTo(tenantId);
        assertThat(audit.getOrgId()).isEqualTo(tenantId);
        assertThat(audit.getAccessorSubject()).isEqualTo("reader@example.com");
        assertThat(audit.getMeetingId()).isEqualTo(meetingId);
        assertThat(audit.getAnalysisRunId()).isEqualTo(runId);
        assertThat(audit.getResultCount()).isEqualTo(1);
        assertThat(audit.getTraceId()).isNull();
    }
}
