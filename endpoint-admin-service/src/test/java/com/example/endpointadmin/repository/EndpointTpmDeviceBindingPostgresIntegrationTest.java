package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointTpmDeviceBinding;
import jakarta.persistence.EntityManager;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.6 #548 slice-1 step-4 — the PG-level proof of the binding's security invariant (Codex {@code 019efada}:
 * merge-blocking). The single-active partial unique index and the re-enrollment revoke-before-insert ordering are
 * DB-level guarantees the canonical session verifier will rely on; the H2/mock slice tests cannot prove them.
 *
 * <p>Mirrors the existing {@code *PostgresIntegrationTest} pattern: PG 16 Testcontainer + Flyway + ddl-auto=validate.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointTpmDeviceBindingPostgresIntegrationTest {

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
    private EndpointTpmDeviceBindingRepository bindings;

    @Autowired
    private EndpointEnrollmentRepository enrollments;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final byte[] AK_NAME = {0x00, 0x0b, 0x11, 0x22};

    private UUID tenant;
    private UUID device;
    private UUID enroll1;
    private UUID enroll2;

    @BeforeEach
    void seedDeviceAndEnrollments() {
        tenant = UUID.randomUUID();
        device = UUID.randomUUID();
        enroll1 = UUID.randomUUID();
        enroll2 = UUID.randomUUID();
        jdbc.update("INSERT INTO endpoint_devices (id, tenant_id, hostname) VALUES (?, ?, ?)",
                device, tenant, "tpm-binding-host");
        seedEnrollment(enroll1, "token-hash-1");
        seedEnrollment(enroll2, "token-hash-2");
    }

    private void seedEnrollment(UUID id, String tokenHash) {
        jdbc.update("INSERT INTO endpoint_enrollments "
                        + "(id, tenant_id, enrollment_token_hash, requested_by_subject, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id, tenant, tokenHash, "tester", Timestamp.from(NOW.plusSeconds(3600)));
    }

    private EndpointTpmDeviceBinding binding(UUID enrollmentId) {
        return new EndpointTpmDeviceBinding(tenant, device, enrollmentId, AK_NAME,
                "akpub-sha", "ekcert-sha", "spki-sha", NOW, NOW);
    }

    @Test
    void partialUniqueRejectsTwoActiveBindings() {
        bindings.saveAndFlush(binding(enroll1));
        // a second ACTIVE (revoked_at IS NULL) binding for the SAME (tenant, device) must be rejected by the
        // partial unique index uq_tpm_binding_active_device — fail-closed, never a silent second active row.
        assertThatThrownBy(() -> bindings.saveAndFlush(binding(enroll2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revokeActiveBeforeInsert_leavesExactlyOneActiveAndSoftRevokesPrior() {
        // The recordBinding ordering at the DB level (Phase 1.5 delegates the supersede to the same revokeActive):
        // revoke the prior active (bulk UPDATE) BEFORE inserting the replacement, so the partial unique
        // uq_tpm_binding_active_device never momentarily sees two active rows.
        bindings.saveAndFlush(binding(enroll1));
        bindings.revokeActive(tenant, device, NOW.plusSeconds(10), "REENROLLMENT_SUPERSEDED");
        entityManager.flush();
        entityManager.clear();
        bindings.saveAndFlush(binding(enroll2));

        Optional<EndpointTpmDeviceBinding> active =
                bindings.findByTenantIdAndDeviceIdAndRevokedAtIsNull(tenant, device);
        assertThat(active).isPresent();
        assertThat(active.get().getEndpointEnrollmentId()).isEqualTo(enroll2);
        assertThat(bindings.count()).isEqualTo(2L); // prior is kept, soft-revoked (not deleted)

        EndpointTpmDeviceBinding prior = bindings.findByEndpointEnrollmentId(enroll1).orElseThrow();
        assertThat(prior.getRevokedAt()).isNotNull();
        assertThat(prior.getRevokedReason()).isEqualTo("REENROLLMENT_SUPERSEDED");
    }
}
