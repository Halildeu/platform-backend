package com.serban.notify.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V7→V8 data preservation harness (Codex 019dfdec iter-1 P1 absorb).
 *
 * <p>Standard {@link com.serban.notify.AbstractPostgresTest} pattern Flyway'i
 * en son version'a kadar koşturur — V7→V8 ara-step preservation testi
 * yapamaz. Bu harness sırayla:
 * <ol>
 *   <li>Fresh PG container start</li>
 *   <li>Flyway target=7 migrate (V1..V7)</li>
 *   <li>V1 audit_event tablosuna 100 row insert</li>
 *   <li>Flyway latest migrate (V8 cutover)</li>
 *   <li>audit_event_v2'de 100 row mevcut + sample data + sequence continuity</li>
 * </ol>
 *
 * <p>Empty migration case: aynı sequence, 0 row insert.
 */
@Testcontainers
class AuditPartitionV8DataPreservationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("postgres:16-alpine"))
        .withDatabaseName("notify_v8_preservation")
        .withUsername("notify_test")
        .withPassword("notify_test");

    @org.junit.jupiter.api.BeforeEach
    void cleanSchema() throws Exception {
        // Codex 019dfdec iter-2 P1 absorb: V1 hardcodes CREATE SCHEMA notify;
        // Flyway schemas() override doesn't propagate to migration SQL. So we
        // share the single PG container but drop+recreate notify schema +
        // Flyway history before each test (true isolation).
        try (Connection conn = newConnection();
             Statement s = conn.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS notify CASCADE");
            s.execute("DROP TABLE IF EXISTS public.flyway_schema_history");
        }
    }

    @Test
    void v7ToV8MigrationPreservesData() throws Exception {
        try (Connection conn = newConnection()) {
            // 1. Apply V1..V7 (target=7)
            org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas("notify")
                .target("7")
                .load();
            flyway.migrate();

            // 2. Insert 100 rows into V1 audit_event
            try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO notify.audit_event "
                    + "(intent_id, event_type, org_id, topic_key, occurred_at) "
                    + "VALUES (?, ?, ?, ?, ?)")) {
                OffsetDateTime now = OffsetDateTime.now();
                for (int i = 0; i < 100; i++) {
                    ps.setString(1, "preservation-intent-" + i);
                    ps.setString(2, "TEST_PRESERVATION");
                    ps.setString(3, "default");
                    ps.setString(4, "test.preservation");
                    ps.setObject(5, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            // Verify pre-migration count
            int preCount = countAuditEvent(conn);
            assertThat(preCount).isEqualTo(100);

            // 3. Apply V8 (latest)
            org.flywaydb.core.Flyway flywayV8 = org.flywaydb.core.Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas("notify")
                .load();
            flywayV8.migrate();

            // 4. Verify all 100 rows in audit_event_v2
            int postCount = countAuditEventV2(conn);
            assertThat(postCount).isEqualTo(100);

            // 5. Verify sample data preservation
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT intent_id, event_type, org_id FROM notify.audit_event_v2 "
                         + "WHERE intent_id = 'preservation-intent-50'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("intent_id")).isEqualTo("preservation-intent-50");
                assertThat(rs.getString("event_type")).isEqualTo("TEST_PRESERVATION");
                assertThat(rs.getString("org_id")).isEqualTo("default");
            }

            // 6. Verify legacy table preserved
            int legacyCount = countTable(conn, "notify.audit_event_legacy");
            assertThat(legacyCount).isEqualTo(100);

            // 7. Verify sequence continuity (next value > 100)
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT nextval('notify.audit_event_v2_id_seq')")) {
                assertThat(rs.next()).isTrue();
                long nextVal = rs.getLong(1);
                assertThat(nextVal).isGreaterThan(100);
            }
        }
    }

    @Test
    void v7ToV8MigrationEmptyTableNoSetvalError() throws Exception {
        // Codex 019dfdec iter-1 P0 #1 absorb: empty audit table should NOT trigger
        // setval(seq, 0, true) "value 0 is out of bounds" error.
        // Codex iter-2 P1 absorb: same `notify` schema (V1 hardcoded);
        // BeforeEach cleanSchema() ensures isolation.
        try (Connection conn = newConnection()) {
            org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas("notify")
                .target("7")
                .load();
            flyway.migrate();

            // 0 rows pre-migration
            int preCount = jdbcTableCount(conn, "notify.audit_event");
            assertThat(preCount).isEqualTo(0);

            // V8 should succeed with empty table (no setval out-of-bounds)
            org.flywaydb.core.Flyway flywayV8 = org.flywaydb.core.Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .schemas("notify")
                .load();
            flywayV8.migrate();

            // First insert via view should get id=1 (sequence reset to 1)
            try (Statement s = conn.createStatement()) {
                s.execute(
                    "INSERT INTO notify.audit_event "
                        + "(intent_id, event_type, org_id, topic_key, occurred_at) "
                        + "VALUES ('first-insert', 'TEST', 'default', 'test', NOW())");
            }
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery(
                     "SELECT id FROM notify.audit_event_v2 WHERE intent_id='first-insert'")) {
                assertThat(rs.next()).isTrue();
                long id = rs.getLong(1);
                assertThat(id).isPositive();  // got an id, no setval error
            }
        }
    }

    private Connection newConnection() throws Exception {
        return java.sql.DriverManager.getConnection(
            PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private int countAuditEvent(Connection conn) throws Exception {
        return jdbcTableCount(conn, "notify.audit_event");
    }

    private int countAuditEventV2(Connection conn) throws Exception {
        return jdbcTableCount(conn, "notify.audit_event_v2");
    }

    private int countTable(Connection conn, String fullName) throws Exception {
        return jdbcTableCount(conn, fullName);
    }

    private int jdbcTableCount(Connection conn, String fullName) throws Exception {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + fullName)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
