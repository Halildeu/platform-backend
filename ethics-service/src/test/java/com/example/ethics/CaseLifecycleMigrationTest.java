package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 35 ES-301A — migrations against a database that already has rows in it.
 *
 * <p>Every other test in this module starts from an empty schema, and that is precisely the
 * shape of database a data migration cannot fail on. {@code V9} reorders no rows when there
 * are none: its backfill matched nothing, the ordering of the constraint drop around it did
 * not matter, and 145 tests plus a real-PostgreSQL run all passed a migration the live cell
 * then refused on contact with 29 rows of {@code IN_REVIEW}.
 *
 * <p>So this test seeds the state history actually had — cases in the old vocabulary, some
 * answered and some not — <em>between</em> {@code V8} and {@code V9}, and lets the migration
 * meet it. Anything that only breaks on existing data belongs here rather than in a test
 * whose fixture is a fresh schema.
 */
@Testcontainers(disabledWithoutDocker = true)
class CaseLifecycleMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    // One container, but a schema per test. Sharing a schema would make these
    // order-dependent: the migration test needs a database still on V8, and whichever
    // test ran first would decide whether the other one had one.
    private static Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .table("ethics_flyway_history")
                .locations("classpath:db/migration", "classpath:db/vendor/postgresql")
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion(target))
                .load();
    }

    private static Connection open(String schema) throws Exception {
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return connection;
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    @DisplayName("V9, eski sözlükte gerçek satırlar varken de uygulanır")
    void migrationSurvivesRowsWrittenUnderTheOldVocabulary() throws Exception {
        String schema = "es301a_backfill";
        flyway(schema, "8").migrate();

        UUID org = UUID.randomUUID();
        UUID answered = UUID.randomUUID();
        UUID unanswered = UUID.randomUUID();
        UUID internalOnly = UUID.randomUUID();
        Instant opened = Instant.parse("2026-07-01T09:00:00Z");
        Instant replied = Instant.parse("2026-07-03T09:00:00Z");

        try (Connection connection = open(schema); Statement write = connection.createStatement()) {
            for (UUID id : new UUID[] {answered, unanswered, internalOnly}) {
                // The old vocabulary, written by the old code.
                String status = id.equals(unanswered) ? "NEW" : "IN_REVIEW";
                write.execute(
                        "INSERT INTO ethics_cases (id, org_id, product_id, status, version, created_at, updated_at)"
                                + " VALUES ('" + id + "', '" + org + "', 'etik-speak', '" + status
                                + "', 0, '" + opened + "', '" + opened + "')");
            }
            // A case the reporter heard from, a case nobody replied to, and a case whose only
            // staff activity was an internal note the reporter never saw.
            write.execute(
                    "INSERT INTO ethics_messages (id, case_id, author_type, visibility, body, idempotency_key, created_at)"
                            + " VALUES ('" + UUID.randomUUID() + "', '" + answered
                            + "', 'STAFF', 'REPORTER_VISIBLE', 'Bildiriminiz alindi', 'k1', '" + replied + "')");
            write.execute(
                    "INSERT INTO ethics_messages (id, case_id, author_type, visibility, body, idempotency_key, created_at)"
                            + " VALUES ('" + UUID.randomUUID() + "', '" + internalOnly
                            + "', 'STAFF', 'INTERNAL', 'Ic not', 'k2', '" + replied + "')");
        }

        // The step that used to fail: the backfill runs while the old CHECK is still in force.
        flyway(schema, "9").migrate();

        try (Connection connection = open(schema)) {
            assertThat(count(connection, "SELECT count(*) FROM ethics_cases WHERE status = 'IN_REVIEW'"))
                    .as("eski sozlukte satir kalmamali")
                    .isZero();
            assertThat(count(connection, "SELECT count(*) FROM ethics_cases WHERE status = 'ASSESSING'"))
                    .as("IN_REVIEW satirlari ASSESSING'e tasinmali, INVESTIGATING'e degil")
                    .isEqualTo(2);
            assertThat(count(connection,
                    "SELECT count(*) FROM ethics_cases WHERE id = '" + answered + "'"
                            + " AND acknowledged_at = TIMESTAMP WITH TIME ZONE '" + replied + "'"))
                    .as("teyit, ihbarciya giden ilk mesajin zamani olmali")
                    .isEqualTo(1);
            assertThat(count(connection,
                    "SELECT count(*) FROM ethics_cases WHERE acknowledged_at IS NULL"))
                    .as("yanitsiz dava ve yalnizca ic notu olan dava teyitsiz kalmali")
                    .isEqualTo(2);
            assertThat(count(connection,
                    "SELECT count(*) FROM ethics_cases WHERE outcome IS NOT NULL OR closed_at IS NOT NULL"))
                    .as("acik davalarda sonuc ve kapanis tarihi bos kalmali")
                    .isZero();
        }
    }

    /**
     * The conclusion invariant, checked against the database rather than the service: a writer
     * that goes around the application must still not be able to leave a case closed with no
     * finding, or carrying a finding while open.
     */
    @Test
    @DisplayName("kapanış bütünlüğü uygulama katmanı atlansa da veritabanında zorunlu")
    void closureInvariantHoldsAgainstDirectWrites() throws Exception {
        String schema = "es301a_closure";
        flyway(schema, "9").migrate();

        UUID org = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-01T09:00:00Z");
        try (Connection connection = open(schema); Statement write = connection.createStatement()) {
            write.execute(
                    "INSERT INTO ethics_cases (id, org_id, product_id, status, version, created_at, updated_at)"
                            + " VALUES ('" + id + "', '" + org + "', 'etik-speak', 'NEW', 0, '" + now + "', '" + now + "')");

            assertThatThrownBy(() ->
                            write.execute("UPDATE ethics_cases SET status = 'CLOSED' WHERE id = '" + id + "'"))
                    .as("sonucsuz kapanis veritabani seviyesinde reddedilmeli")
                    .hasMessageContaining("ck_ethics_case_closure");

            assertThatThrownBy(() ->
                            write.execute("UPDATE ethics_cases SET outcome = 'SUBSTANTIATED' WHERE id = '" + id + "'"))
                    .as("acik davaya sonuc yazilmasi reddedilmeli")
                    .hasMessageContaining("ck_ethics_case_closure");

            assertThatThrownBy(() ->
                            write.execute("UPDATE ethics_cases SET status = 'PAUSED' WHERE id = '" + id + "'"))
                    .as("sozluk disi statu reddedilmeli")
                    .hasMessageContaining("ck_ethics_case_status");
        }
    }
}
