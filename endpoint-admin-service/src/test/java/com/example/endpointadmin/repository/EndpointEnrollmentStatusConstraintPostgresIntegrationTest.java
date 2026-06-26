package com.example.endpointadmin.repository;

import org.junit.jupiter.api.BeforeEach;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.6 #548 — V77 regression (Codex {@code 019f03e2} REVISE, merge-blocking). The
 * endpoint_enrollments status CHECK must accept the TPM lifecycle statuses the /attest flow writes
 * (TPM_IN_PROGRESS set before the Vault call as a double-issue guard; TPM_FAILED on verify/issue
 * failure). The original V2 CHECK only allowed PENDING/CONSUMED/EXPIRED/REVOKED, so a real /attest
 * on PostgreSQL failed with check_violation (SQLState 23514) before binding — found by the live
 * #548 enrollment on a real Intel ADL fTPM and masked by H2/ddl-auto unit slices. This PG-level
 * test keeps the canonical-status CHECK and the migration honest.
 *
 * <p>Mirrors the existing {@code *PostgresIntegrationTest} pattern: PG 16 Testcontainer + Flyway + ddl-auto=validate.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointEnrollmentStatusConstraintPostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
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
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
    }

    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    private UUID enrollment;

    @BeforeEach
    void seedEnrollment() {
        enrollment = UUID.randomUUID();
        jdbc.update("INSERT INTO endpoint_enrollments "
                        + "(id, tenant_id, enrollment_token_hash, requested_by_subject, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                enrollment, UUID.randomUUID(), "v77-token-hash", "tester",
                Timestamp.from(NOW.plusSeconds(3600)));
    }

    @Test
    void tpmInProgressStatusIsAccepted() {
        assertThat(jdbc.update("UPDATE endpoint_enrollments SET status = 'TPM_IN_PROGRESS' WHERE id = ?", enrollment))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM endpoint_enrollments WHERE id = ?", String.class, enrollment))
                .isEqualTo("TPM_IN_PROGRESS");
    }

    @Test
    void tpmFailedStatusIsAccepted() {
        assertThat(jdbc.update("UPDATE endpoint_enrollments SET status = 'TPM_FAILED' WHERE id = ?", enrollment))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM endpoint_enrollments WHERE id = ?", String.class, enrollment))
                .isEqualTo("TPM_FAILED");
    }

    @Test
    void legacyStatusesStillAccepted() {
        for (String s : new String[] {"PENDING", "CONSUMED", "EXPIRED", "REVOKED"}) {
            assertThat(jdbc.update("UPDATE endpoint_enrollments SET status = ? WHERE id = ?", s, enrollment))
                    .as("legacy status %s must remain accepted", s)
                    .isEqualTo(1);
        }
    }

    @Test
    void unknownStatusIsRejectedByCheckConstraint() {
        assertThatThrownBy(() ->
                jdbc.update("UPDATE endpoint_enrollments SET status = 'BOGUS' WHERE id = ?", enrollment))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void canonicalStatusConstraintExistsAfterV77() {
        String def = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'ck_endpoint_enrollments_status'",
                String.class);
        assertThat(def)
                .contains("TPM_IN_PROGRESS").contains("TPM_FAILED")
                .contains("PENDING").contains("CONSUMED").contains("EXPIRED").contains("REVOKED");
    }
}
