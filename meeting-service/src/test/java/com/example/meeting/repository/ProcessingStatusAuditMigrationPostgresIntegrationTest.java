package com.example.meeting.repository;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ProcessingStatusAuditMigrationPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("meeting").withUsername("test").withPassword("test");

    @Test
    void upgradePreservesHistoricalReadAuditsAndEnforcesMutuallyExclusiveScope() {
        String schema = "processing_status_upgrade";
        var config = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema).schemas(schema);
        config.target("15").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID tenant = UUID.randomUUID(), meeting = UUID.randomUUID(), run = UUID.randomUUID(), session = UUID.randomUUID();
        UUID old = insert(jdbc, tenant, meeting, run, null, "CANONICAL_RESULT_READ", false);
        config.target("16").load().migrate();
        var row = jdbc.queryForMap("SELECT * FROM processing_status_upgrade.meeting_intelligence_result_access_audit WHERE id=?", old);
        assertThat(row.get("analysis_run_id")).isEqualTo(run);
        assertThat(row.get("session_id")).isNull();
        assertThat(row.get("result_count")).isEqualTo(1);
        UUID status = insert(jdbc, tenant, meeting, null, session, "SESSION_PROCESSING_STATUS_READ", true);
        assertThat(jdbc.queryForObject("SELECT session_id FROM processing_status_upgrade.meeting_intelligence_result_access_audit WHERE id=?",
                UUID.class, status)).isEqualTo(session);
        // Both old read types still require a real run and must not claim a session-only read.
        for (String type : new String[]{"CANONICAL_RESULT_READ", "CANONICAL_TRANSCRIPT_READ"}) {
            assertThatThrownBy(() -> insert(jdbc, tenant, meeting, null, null, type, true))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> insert(jdbc, tenant, meeting, run, session, type, true))
                    .isInstanceOf(DataIntegrityViolationException.class);
            insert(jdbc, tenant, meeting, run, null, type, true);
        }
        assertThatThrownBy(() -> insert(jdbc, tenant, meeting, null, null, "SESSION_PROCESSING_STATUS_READ", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, tenant, meeting, run, session, "SESSION_PROCESSING_STATUS_READ", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexname='idx_meeting_result_access_retention'",
                Integer.class, schema)).isEqualTo(1);
        assertThat(jdbc.update("DELETE FROM processing_status_upgrade.meeting_intelligence_result_access_audit WHERE id=?", status)).isEqualTo(1);
    }

    private UUID insert(JdbcTemplate jdbc, UUID tenant, UUID meeting, UUID run, UUID session, String type, boolean migrated) {
        UUID id = UUID.randomUUID();
        String sql = "INSERT INTO processing_status_upgrade.meeting_intelligence_result_access_audit "
                + "(id,tenant_id,org_id,accessor_subject,meeting_id,analysis_run_id,access_type,result_count,accessed_at"
                + (migrated ? ",session_id) VALUES (?,?,?,'owner',?,?,?,1,now(),?)" : ") VALUES (?,?,?,'owner',?,?,?,1,now())");
        if (migrated) { jdbc.update(sql, id, tenant, tenant, meeting, run, type, session); }
        else { jdbc.update(sql, id, tenant, tenant, meeting, run, type); }
        return id;
    }
}
