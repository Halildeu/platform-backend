package com.example.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class AnalysisRecordingOutcomeMigrationPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("meeting").withUsername("test").withPassword("test");

    @Test
    void upgradeKeepsHistoricalResultsUnknownAndDoesNotInferClosureFromAnalysisSuccess() {
        String schema = "analysis_closure_upgrade";
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema).schemas(schema).target("18").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID tenant = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO analysis_closure_upgrade.meetings
                (id, tenant_id, title, status, organizer_subject, created_by_subject, last_updated_by_subject,
                 created_at, updated_at)
                VALUES (?, ?, 'fixture', 'SCHEDULED', 'owner', 'owner', 'owner', now(), now())
                """, meeting, tenant);
        jdbc.update("""
                INSERT INTO analysis_closure_upgrade.meeting_analysis_runs
                (analysis_run_id, meeting_id, tenant_id, org_id, transcript_session_id, transcript_sha256,
                 analyzer_contract_version, payload_hash, generated_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, '5-adr0043', ?, now(), now(), now())
                """, run, meeting, tenant, tenant, UUID.randomUUID().toString(), "a".repeat(64), "b".repeat(64));
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema).schemas(schema).load().migrate();
        var row = jdbc.queryForMap("SELECT recording_outcome, recording_incomplete_reason "
                + "FROM analysis_closure_upgrade.meeting_analysis_runs WHERE analysis_run_id=?", run);
        assertThat(row.get("recording_outcome")).isEqualTo("UNKNOWN");
        assertThat(row.get("recording_incomplete_reason")).isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE analysis_closure_upgrade.meeting_analysis_runs "
                + "SET recording_outcome='FINISHED' WHERE analysis_run_id=?", run))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Retention/erasure must remain able to delete the immutable metadata.
        assertThat(jdbc.update("DELETE FROM analysis_closure_upgrade.meeting_analysis_runs WHERE analysis_run_id=?", run))
                .isEqualTo(1);
    }
}
