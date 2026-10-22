package com.example.endpointadmin.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V83 managed wallpaper asset store PG IT (platform-backend#1203).
 *
 * <p>Exercises the invariants only PostgreSQL enforces: the image-type domain,
 * the 10 MiB cap, the stored-size-equals-content-length honesty check, the
 * content-addressing unique index, the org_id compat fill, and the immutability
 * trigger that keeps a hash naming exactly one byte sequence forever.
 */
@Testcontainers
class V83DisplayPolicyAssetsPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String HASH = "b".repeat(64);
    private static final int CAP = 10 * 1024 * 1024;

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    private JdbcTemplate migratedJdbc() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        Flyway.configure()
                .dataSource(ds)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .target("83")
                .load()
                .migrate();
        return jdbc;
    }

    /** Inserts {@code sizeBytes} zero bytes generated inside PG (no JVM buffer). */
    private void insert(JdbcTemplate jdbc, UUID tenant, String sha, String type,
                        int declaredSize, int actualBytes) {
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_display_policy_assets "
                        + "(id, tenant_id, sha256, content_type, size_bytes, content, "
                        + " created_by_subject, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, decode(repeat('00', ?), 'hex'), 'admin', ?)",
                UUID.randomUUID(), tenant, sha, type, declaredSize, actualBytes,
                Timestamp.from(Instant.now()));
    }

    @Test
    void validAssetIsStoredAndOrgIdIsCompatFilled() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        insert(jdbc, tenant, HASH, "image/png", 64, 64);

        UUID orgId = jdbc.queryForObject("SELECT org_id FROM " + SCHEMA
                + ".endpoint_display_policy_assets WHERE sha256 = ?", UUID.class, HASH);
        assertThat(orgId).isEqualTo(tenant);
    }

    @Test
    void onlyTheThreeRenderableImageTypesAreAccepted() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        insert(jdbc, tenant, "1".repeat(64), "image/jpeg", 8, 8);
        insert(jdbc, tenant, "2".repeat(64), "image/bmp", 8, 8);

        assertThatThrownBy(() -> insert(jdbc, tenant, "3".repeat(64), "application/x-msdownload", 8, 8))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, tenant, "4".repeat(64), "image/gif", 8, 8))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void storedSizeMustEqualTheActualContentLength() {
        JdbcTemplate jdbc = migratedJdbc();
        // A truncated write must not pass for a complete image.
        assertThatThrownBy(() -> insert(jdbc, UUID.randomUUID(), HASH, "image/png", 100, 99))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theTenMebibyteCapHoldsAtTheBoundary() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        insert(jdbc, tenant, "5".repeat(64), "image/png", CAP, CAP);

        assertThatThrownBy(() -> insert(jdbc, tenant, "6".repeat(64), "image/png", CAP + 1, CAP + 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shaMustBeLowercaseHexOfExactLength() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> insert(jdbc, tenant, "B".repeat(64), "image/png", 8, 8))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(jdbc, tenant, "b".repeat(63) + "z", "image/png", 8, 8))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneImagePerHashPerOrgButOtherOrgsAreIndependent() {
        JdbcTemplate jdbc = migratedJdbc();
        UUID tenant = UUID.randomUUID();
        insert(jdbc, tenant, HASH, "image/png", 8, 8);

        assertThatThrownBy(() -> insert(jdbc, tenant, HASH, "image/png", 8, 8))
                .isInstanceOf(DataIntegrityViolationException.class);
        // The same image uploaded by another tenant is that tenant's own row.
        insert(jdbc, UUID.randomUUID(), HASH, "image/png", 8, 8);
    }

    @Test
    void assetsAreImmutableOnceStored() {
        JdbcTemplate jdbc = migratedJdbc();
        insert(jdbc, UUID.randomUUID(), HASH, "image/png", 8, 8);

        assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA
                + ".endpoint_display_policy_assets SET content_type = 'image/bmp' WHERE sha256 = ?", HASH))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA
                + ".endpoint_display_policy_assets WHERE sha256 = ?", HASH))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }
}
