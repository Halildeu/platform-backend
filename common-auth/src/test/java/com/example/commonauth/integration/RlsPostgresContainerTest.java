package com.example.commonauth.integration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RLS (Row-Level Security) integration test with real PostgreSQL via Testcontainers.
 * CNS-20260411-003 PR3: RLS parity with devops/postgres scripts.
 *
 * Verifies cross-company data isolation using actual PostgreSQL RLS policies
 * matching the production devops/postgres/02-rls-policies.sql.
 */
@Testcontainers
@Tag("integration")
class RlsPostgresContainerTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("testdb")
            .withUsername("testuser")
            .withPassword("testpass");

    /** Owner/superuser connection: schema + policy setup only. */
    private Connection conn;

    /**
     * The connection every assertion runs on. It must NOT be a superuser: Postgres lets
     * superusers (and BYPASSRLS roles) skip row security entirely, and FORCE ROW LEVEL
     * SECURITY does not change that -- it only extends enforcement to the table owner.
     * Before this existed the whole class ran on the Testcontainers POSTGRES_USER, which
     * is the bootstrap superuser, so no policy was ever applied and the suite could not
     * have detected a broken policy. It never ran in CI, so nobody found out (#929).
     */
    private Connection probeConn;

    @BeforeEach
    void setup() throws Exception {
        conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        conn.setAutoCommit(false);

        try (Statement st = conn.createStatement()) {
            // Create schema and table matching production structure
            st.execute("CREATE SCHEMA IF NOT EXISTS user_service");
            st.execute("""
                CREATE TABLE IF NOT EXISTS user_service.users (
                    id BIGSERIAL PRIMARY KEY,
                    username VARCHAR(100),
                    company_id BIGINT
                )
                """);
            st.execute("DELETE FROM user_service.users");

            // Insert test data: 3 users across 2 companies
            st.execute("INSERT INTO user_service.users (username, company_id) VALUES ('alice', 1)");
            st.execute("INSERT INTO user_service.users (username, company_id) VALUES ('bob', 1)");
            st.execute("INSERT INTO user_service.users (username, company_id) VALUES ('charlie', 2)");
            st.execute("INSERT INTO user_service.users (username, company_id) VALUES ('global_user', NULL)");

            // Enable RLS (matching devops/postgres/02-rls-policies.sql)
            st.execute("ALTER TABLE user_service.users ENABLE ROW LEVEL SECURITY");
            st.execute("ALTER TABLE user_service.users FORCE ROW LEVEL SECURITY");

            // Drop existing policy if re-running
            st.execute("""
                DO $$ BEGIN
                    DROP POLICY IF EXISTS company_scope_users ON user_service.users;
                END $$
                """);

            // Create RLS policy matching production
            st.execute("""
                CREATE POLICY company_scope_users ON user_service.users
                FOR ALL USING (
                    company_id IS NULL
                    OR current_setting('app.scope.company_ids', true) IS NULL
                    OR current_setting('app.scope.company_ids', true) = ''
                    OR current_setting('app.scope.bypass_rls', true) = 'true'
                    OR company_id = ANY(
                        string_to_array(current_setting('app.scope.company_ids', true), ',')::bigint[]
                    )
                )
                """);
            // Least-privilege subject for the assertions. Cluster-wide object, so the
            // create is guarded for re-runs against a reused container.
            st.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rls_probe') THEN
                        CREATE ROLE rls_probe LOGIN NOSUPERUSER NOBYPASSRLS
                            PASSWORD 'rls-probe-test-only';
                    END IF;
                END $$
                """);
            st.execute("GRANT USAGE ON SCHEMA user_service TO rls_probe");
            st.execute("GRANT SELECT ON user_service.users TO rls_probe");
        }
        conn.commit();

        probeConn = DriverManager.getConnection(
                postgres.getJdbcUrl(), "rls_probe", "rls-probe-test-only");
        probeConn.setAutoCommit(false);  // SET LOCAL needs a transaction
    }

    @AfterEach
    void teardown() throws Exception {
        if (probeConn != null && !probeConn.isClosed()) {
            probeConn.rollback();
            probeConn.close();
        }
        if (conn != null && !conn.isClosed()) {
            conn.rollback();
            conn.close();
        }
    }

    @Test
    @DisplayName("No scope set → all rows visible (dev mode)")
    void noScope_allVisible() throws Exception {
        var users = queryUsers(probeConn);
        assertEquals(4, users.size(), "All users visible when no scope set");
    }

    @Test
    @DisplayName("Scope company 1 → only company 1 + NULL users")
    void scopeCompany1_onlyCompany1() throws Exception {
        try (Statement st = probeConn.createStatement()) {
            st.execute("SET LOCAL app.scope.company_ids = '1'");
        }
        var users = queryUsers(probeConn);
        assertEquals(3, users.size(), "alice + bob (company 1) + global_user (NULL)");
        assertTrue(users.contains("alice"));
        assertTrue(users.contains("bob"));
        assertTrue(users.contains("global_user"));
        assertFalse(users.contains("charlie"), "charlie (company 2) must be filtered");
    }

    @Test
    @DisplayName("Scope company 2 → only company 2 + NULL users")
    void scopeCompany2_onlyCompany2() throws Exception {
        try (Statement st = probeConn.createStatement()) {
            st.execute("SET LOCAL app.scope.company_ids = '2'");
        }
        var users = queryUsers(probeConn);
        assertEquals(2, users.size(), "charlie (company 2) + global_user (NULL)");
        assertTrue(users.contains("charlie"));
        assertTrue(users.contains("global_user"));
    }

    @Test
    @DisplayName("Multi-company scope → both companies visible")
    void multiCompanyScope() throws Exception {
        try (Statement st = probeConn.createStatement()) {
            st.execute("SET LOCAL app.scope.company_ids = '1,2'");
        }
        var users = queryUsers(probeConn);
        assertEquals(4, users.size(), "All users visible with both companies in scope");
    }

    @Test
    @DisplayName("Bypass RLS → all rows visible regardless of scope")
    void bypassRls_allVisible() throws Exception {
        try (Statement st = probeConn.createStatement()) {
            st.execute("SET LOCAL app.scope.company_ids = '1'");
            st.execute("SET LOCAL app.scope.bypass_rls = 'true'");
        }
        var users = queryUsers(probeConn);
        assertEquals(4, users.size(), "SuperAdmin bypass: all users visible");
    }

    @Test
    @DisplayName("Cross-company isolation: company 1 user cannot see company 2 data")
    void crossCompanyIsolation() throws Exception {
        try (Statement st = probeConn.createStatement()) {
            st.execute("SET LOCAL app.scope.company_ids = '1'");
        }
        var users = queryUsers(probeConn);
        assertFalse(users.contains("charlie"),
                "CRITICAL: Company 1 scope must NOT see company 2 user (charlie)");
    }

    private List<String> queryUsers(Connection c) throws Exception {
        var result = new ArrayList<String>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT username FROM user_service.users")) {
            while (rs.next()) {
                result.add(rs.getString("username"));
            }
        }
        return result;
    }
}
