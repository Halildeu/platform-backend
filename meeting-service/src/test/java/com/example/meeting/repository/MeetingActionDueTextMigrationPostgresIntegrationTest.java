package com.example.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MeetingActionDueTextMigrationPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("meeting").withUsername("test").withPassword("test");

    @TempDir
    Path legacyMigrations;

    @Test
    void upgradePreservesLegacyRowsAndOldMigrationValidationWithoutDroppingSourceText() throws Exception {
        String schema = "due_text_upgrade";
        try (var migrations = Files.list(Path.of("src/main/resources/db/migration"))) {
            for (Path path : migrations.filter(p -> p.getFileName().toString()
                    .matches("V(?:[1-9]|1[0-4])__.*\\.sql")).toList()) {
                Files.copy(path, legacyMigrations.resolve(path.getFileName()));
            }
        }
        Flyway oldRuntime = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema).schemas(schema)
                .locations("filesystem:" + legacyMigrations).load();
        oldRuntime.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID tenant = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        UUID action = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO due_text_upgrade.meetings
                (id, tenant_id, title, status, organizer_subject, created_by_subject, last_updated_by_subject,
                 created_at, updated_at)
                VALUES (?, ?, 'migration fixture', 'SCHEDULED', 'owner', 'owner', 'owner', now(), now())
                """, meeting, tenant);
        jdbc.update("""
                INSERT INTO due_text_upgrade.meeting_actions
                (id, meeting_id, tenant_id, description, created_by_subject, last_updated_by_subject, due_at,
                 created_at, updated_at)
                VALUES (?, ?, ?, 'legacy action', 'owner', 'owner', '2026-07-20T09:00:00Z', now(), now())
                """, action, meeting, tenant);
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .defaultSchema(schema).schemas(schema).load().migrate();

        var row = jdbc.queryForMap("SELECT due_at, due_text FROM due_text_upgrade.meeting_actions WHERE id = ?",
                action);
        assertThat(row.get("due_at")).isNotNull();
        assertThat(row.get("due_text")).isNull();
        var column = jdbc.queryForMap("""
                SELECT is_nullable, character_maximum_length FROM information_schema.columns
                WHERE table_schema = 'due_text_upgrade' AND table_name = 'meeting_actions'
                  AND column_name = 'due_text'
                """);
        assertThat(column.get("is_nullable")).isEqualTo("YES");
        assertThat(column.get("character_maximum_length")).isEqualTo(255);
        jdbc.update("UPDATE due_text_upgrade.meeting_actions SET due_text = ? WHERE id = ?",
                "Perşembe günü", action);
        // The old binary has only V1..V14; the additive future migration must not block its startup.
        oldRuntime.validate();
        jdbc.update("UPDATE due_text_upgrade.meeting_actions SET description = 'legacy update' WHERE id = ?", action);
        assertThat(jdbc.queryForObject("SELECT due_text FROM due_text_upgrade.meeting_actions WHERE id = ?",
                String.class, action)).isEqualTo("Perşembe günü");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE due_text_upgrade.meeting_actions SET due_text = ? WHERE id = ?", "x".repeat(256), action))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
