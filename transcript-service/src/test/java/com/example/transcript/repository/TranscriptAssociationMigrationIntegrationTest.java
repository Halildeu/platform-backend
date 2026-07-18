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

/** Runs the V3 to latest upgrade path against rows written by the old consumer. */
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
    void latestMigrationBackfillsWindowIdentityAndAddsRestartSafeFinalizationState()
            throws Exception {
        migrateTo("3");
        UUID tenant = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        UUID finalizedMeeting = UUID.randomUUID();
        UUID finalizedSession = UUID.randomUUID();
        insertLegacySegment(tenant, meeting, "SES-legacy", 5L);
        insertLegacySegment(tenant, meeting, "SES-legacy", 6L);
        insertLegacySegment(tenant, meeting, "legacy invalid id", 7L);

        migrateTo("6");
        insertLegacyFinalizedAssociation(
                tenant, finalizedMeeting, finalizedSession, "SES-finalized", 2L);

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
            assertThat(singleString(connection,
                    "SELECT finalization_state || ':' || finalization_cycle_version FROM "
                            + SCHEMA + ".transcript_session_associations "
                            + "WHERE tenant_id = ? AND meeting_id = ? AND source_session_id = 'SES-legacy'",
                    tenant, meeting)).isEqualTo("AWAITING_FINISH:0");
            assertThat(singleString(connection,
                    "SELECT finalization_state || ':' || finalization_cycle_version FROM "
                            + SCHEMA + ".transcript_session_associations "
                            + "WHERE tenant_id = ? AND meeting_id = ? AND session_id = ?",
                    tenant, finalizedMeeting, finalizedSession)).isEqualTo("FINALIZED:2");
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

            assertThat(singleString(connection,
                    "SELECT source_window_seq || ':' || source_first_chunk_seq || ':' "
                            + "|| source_last_chunk_seq FROM " + SCHEMA
                            + ".transcript_segments WHERE tenant_id = ? AND meeting_id = ? "
                            + "AND source_session_id = 'SES-legacy' AND source_chunk_seq = 5",
                    tenant, meeting)).isEqualTo("5:5:5");

            // V6 keys replay by tenant + meeting + source session + window.
            // Reusing an external recorder/window in another meeting is legal.
            insertWindowSegment(
                    connection, tenant, UUID.randomUUID(), "SES-legacy", 5L, 5L, 5L);
            assertThatThrownBy(() -> insertWindowSegment(
                    connection, tenant, meeting, "SES-legacy", 5L, 6L, 6L))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState())
                            .isEqualTo("23505"));
            // lastChunkSeq is no longer the idempotency key.
            insertWindowSegment(
                    connection, tenant, meeting, "SES-legacy", 8L, 5L, 5L);

            assertThatThrownBy(() -> insertWindowSegment(
                    connection, tenant, meeting, "SES-invalid-range", 9L, 8L, 7L))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState())
                            .isEqualTo("23514"));
            assertThatThrownBy(() -> insertPartialWindowSegment(
                    connection, tenant, meeting, "SES-partial-window", 10L, 7L))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState())
                            .isEqualTo("23514"));

            assertThat(singleLong(connection,
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = ? AND table_name IN "
                            + "('transcript_finalizations','transcript_event_outbox')",
                    SCHEMA)).isEqualTo(2L);
            assertThat(singleString(connection,
                    "SELECT data_type FROM information_schema.columns "
                            + "WHERE table_schema = ? AND table_name = 'transcript_event_outbox' "
                            + "AND column_name = 'payload'",
                    SCHEMA)).isEqualTo("text");
            assertThat(singleLong(connection,
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = ? "
                            + "AND indexname = 'ux_transcript_segments_direct_stt_window'",
                    SCHEMA)).isEqualTo(1L);
            assertThat(singleLong(connection,
                    "SELECT count(*) FROM information_schema.tables "
                            + "WHERE table_schema = ? "
                            + "AND table_name = 'transcript_meeting_event_inbox'",
                    SCHEMA)).isEqualTo(1L);
            assertThat(singleLong(connection,
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema = ? "
                            + "AND table_name = 'transcript_meeting_event_inbox' "
                            + "AND column_name IN ('event_key','event_type','payload_sha256',"
                            + "'tenant_id','org_id','meeting_id','session_id','source_session_id',"
                            + "'received_at','processed_at')",
                    SCHEMA)).isEqualTo(10L);
            assertThat(singleLong(connection,
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema = ? "
                            + "AND table_name = 'transcript_meeting_event_inbox' "
                            + "AND column_name IN ('payload','transcript','text_draft','text_final')",
                    SCHEMA)).isZero();
            assertThat(singleLong(connection,
                    "SELECT count(*) FROM pg_indexes WHERE schemaname = ? "
                            + "AND indexname IN ('idx_transcript_session_association_finalization_due',"
                            + "'idx_transcript_meeting_event_inbox_scope')",
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

    private void insertLegacyFinalizedAssociation(
            UUID tenant,
            UUID meeting,
            UUID session,
            String sourceSession,
            long finalizationVersion) throws SQLException {
        String sql = "INSERT INTO " + SCHEMA + ".transcript_session_associations "
                + "(id,tenant_id,org_id,meeting_id,source_system,source_session_id,session_id,"
                + "status,resolution_attempts,finalization_version,created_at,updated_at,version) "
                + "VALUES (?,?,?,?,?,?,?,'RESOLVED',0,?,?,?,0)";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenant);
            statement.setObject(3, tenant);
            statement.setObject(4, meeting);
            statement.setString(5, "DIRECT_STT");
            statement.setString(6, sourceSession);
            statement.setObject(7, session);
            statement.setLong(8, finalizationVersion);
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(9, now);
            statement.setTimestamp(10, now);
            statement.executeUpdate();
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

    private void insertWindowSegment(
            Connection connection,
            UUID tenant,
            UUID meeting,
            String sourceSession,
            long windowSeq,
            long firstChunkSeq,
            long lastChunkSeq)
            throws SQLException {
        String sql = "INSERT INTO " + SCHEMA + ".transcript_segments "
                + "(id,tenant_id,org_id,meeting_id,start_time,end_time,text_draft,status,"
                + "source_system,source_session_id,source_chunk_seq,source_window_seq,"
                + "source_first_chunk_seq,source_last_chunk_seq,created_at,updated_at,version) "
                + "VALUES (?,?,?,?,0,1,'window draft','DRAFT','DIRECT_STT',?,?,?,?,?, ?,?,0)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenant);
            statement.setObject(3, tenant);
            statement.setObject(4, meeting);
            statement.setString(5, sourceSession);
            statement.setLong(6, lastChunkSeq);
            statement.setLong(7, windowSeq);
            statement.setLong(8, firstChunkSeq);
            statement.setLong(9, lastChunkSeq);
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(10, now);
            statement.setTimestamp(11, now);
            statement.executeUpdate();
        }
    }

    private void insertPartialWindowSegment(
            Connection connection,
            UUID tenant,
            UUID meeting,
            String sourceSession,
            long windowSeq,
            long lastChunkSeq)
            throws SQLException {
        String sql = "INSERT INTO " + SCHEMA + ".transcript_segments "
                + "(id,tenant_id,org_id,meeting_id,start_time,end_time,text_draft,status,"
                + "source_system,source_session_id,source_chunk_seq,source_window_seq,"
                + "source_last_chunk_seq,created_at,updated_at,version) "
                + "VALUES (?,?,?,?,0,1,'partial window','DRAFT','DIRECT_STT',?,?,?,?, ?,?,0)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, tenant);
            statement.setObject(3, tenant);
            statement.setObject(4, meeting);
            statement.setString(5, sourceSession);
            statement.setLong(6, lastChunkSeq);
            statement.setLong(7, windowSeq);
            statement.setLong(8, lastChunkSeq);
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(9, now);
            statement.setTimestamp(10, now);
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
