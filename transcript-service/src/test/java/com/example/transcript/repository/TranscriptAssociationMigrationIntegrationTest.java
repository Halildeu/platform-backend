package com.example.transcript.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the V3 to V5 upgrade path against rows written by the old consumer. */
@Testcontainers
class TranscriptAssociationMigrationIntegrationTest {

    private static final String SCHEMA = "transcript_service";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("transcript_migration")
                    .withUsername("test")
                    .withPassword("test");

    @Test
    void v4SeedsReconciliationStateAndMovesReplayUniquenessToMeetingBoundary()
            throws Exception {
        migrateTo("3");
        UUID tenant = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        insertLegacySegment(tenant, meeting, "SES-legacy", 5L);
        insertLegacySegment(tenant, meeting, "SES-legacy", 6L);
        insertLegacySegment(tenant, meeting, "legacy invalid id", 7L);

        migrateTo(null);

        try (Connection connection = connection()) {
            assertThat(singleLong(connection,
                    "SELECT count(*) FROM " + SCHEMA + ".transcript_session_associations "
                            + "WHERE tenant_id = ? AND meeting_id = ? AND source_session_id = 'SES-legacy'",
                    tenant, meeting)).isEqualTo(1L);
            assertThat(singleString(connection,
                    "SELECT status FROM " + SCHEMA + ".transcript_session_associations "
                            + "WHERE tenant_id = ? AND meeting_id = ? AND source_session_id = 'SES-legacy'",
                    tenant, meeting)).isEqualTo("PENDING");
            assertThat(singleLong(connection,
                    "SELECT count(*) FROM " + SCHEMA + ".transcript_segments "
                            + "WHERE tenant_id = ? AND meeting_id = ? AND session_id IS NULL",
                    tenant, meeting)).isEqualTo(3L);
            assertThat(singleString(connection,
                    "SELECT status || ':' || last_error_code FROM " + SCHEMA
                            + ".transcript_session_associations "
                            + "WHERE tenant_id = ? AND meeting_id = ? "
                            + "AND source_session_id = 'legacy invalid id'",
                    tenant, meeting)).isEqualTo("DEAD:INVALID_SOURCE_SESSION_ID");

            // V4 replaces the old tenant/source/chunk index. Reusing an external
            // recorder id and chunk sequence in another meeting is now legal.
            insertSegment(connection, tenant, UUID.randomUUID(), "SES-legacy", 5L);
            assertThatThrownBy(() -> insertSegment(
                    connection, tenant, meeting, "SES-legacy", 5L))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState())
                            .isEqualTo("23505"));

            assertThat(singleLong(connection,
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = ? AND table_name IN "
                            + "('transcript_finalizations','transcript_event_outbox')",
                    SCHEMA)).isEqualTo(2L);
        }
    }

    private void migrateTo(String target) {
        var configuration = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private void insertLegacySegment(UUID tenant, UUID meeting, String sourceSession, long chunk)
            throws SQLException {
        try (Connection connection = connection()) {
            insertSegment(connection, tenant, meeting, sourceSession, chunk);
        }
    }

    private void insertSegment(
            Connection connection, UUID tenant, UUID meeting, String sourceSession, long chunk)
            throws SQLException {
        String sql = "INSERT INTO " + SCHEMA + ".transcript_segments "
                + "(id,tenant_id,org_id,meeting_id,start_time,end_time,text_draft,status,"
                + "source_system,source_session_id,source_chunk_seq,created_at,updated_at,version) "
                + "VALUES (?,?,?,?,0,1,'legacy draft','DRAFT','DIRECT_STT',?,?,?, ?,0)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenant);
            statement.setObject(3, tenant);
            statement.setObject(4, meeting);
            statement.setString(5, sourceSession);
            statement.setLong(6, chunk);
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(7, now);
            statement.setTimestamp(8, now);
            statement.executeUpdate();
        }
    }

    private long singleLong(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private String singleString(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
