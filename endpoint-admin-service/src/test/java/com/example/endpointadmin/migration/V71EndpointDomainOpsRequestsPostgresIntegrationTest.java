package com.example.endpointadmin.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V71 regression guard for durable Domain Ops Broker request state.
 *
 * <p>Pins the PostgreSQL-only pieces behind #676: JSONB result shape, bounded
 * TTL, explicit operation allowlist, opaque credential-ref shape, org/tenant
 * parity, and tenant-scoped idempotency uniqueness.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class V71EndpointDomainOpsRequestsPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String DEVICES = SCHEMA + ".endpoint_devices";
    private static final String DOMAIN_OPS = SCHEMA + ".endpoint_domain_ops_requests";
    private static final String HASH = "a".repeat(64);

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
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema",
                () -> SCHEMA);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliesV71AndJsonbResultShapeAcceptsObjects() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID request = insertDomainOps(org, device, "DOMAIN_SECURE_CHANNEL_VERIFY",
                "FAILED", "vault:domain-ops/pilot", HASH, 300);

        assertThat(jdbc.queryForObject(
                "SELECT jsonb_typeof(redacted_result) FROM " + DOMAIN_OPS + " WHERE id = ?",
                String.class,
                request)).isEqualTo("object");
    }

    @Test
    void credentialRefShapeAllowsOpaqueRefsAndRejectsRawOrShellLikeValues() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);

        insertDomainOps(org, device, "GPO_FORCE_REFRESH",
                "DISPATCHED", "os-credential:domain-ops/pilot", HASH, 300);

        assertThatThrownBy(() -> insertDomainOps(org, device, "GPO_FORCE_REFRESH",
                "DISPATCHED", "password=plain", "b".repeat(64), 300))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertDomainOps(org, device, "GPO_FORCE_REFRESH",
                "DISPATCHED", "vault:domain-ops/pilot;rm", "c".repeat(64), 300))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void idempotencyKeyHashUniqueIsTenantScoped() {
        UUID orgA = UUID.randomUUID();
        UUID deviceA = seedDevice(orgA);
        UUID orgB = UUID.randomUUID();
        UUID deviceB = seedDevice(orgB);
        String idempotencyHash = "d".repeat(64);

        insertDomainOps(orgA, deviceA, "CERT_AUTOENROLL_PULSE",
                "FAILED", "secret-ref:domain-ops/pilot", idempotencyHash, 300);
        insertDomainOps(orgB, deviceB, "CERT_AUTOENROLL_PULSE",
                "FAILED", "secret-ref:domain-ops/pilot", idempotencyHash, 300);

        assertThatThrownBy(() -> insertDomainOps(orgA, deviceA, "CERT_AUTOENROLL_PULSE",
                "FAILED", "secret-ref:domain-ops/other", idempotencyHash, 300))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ttlAndOperationChecksFailClosed() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);

        assertThatThrownBy(() -> insertDomainOps(org, device, "AD_USER_PASSWORD_RESET",
                "FAILED", "vault:domain-ops/pilot", HASH, 300))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertDomainOps(org, device, "DOMAIN_SECURE_CHANNEL_VERIFY",
                "FAILED", "vault:domain-ops/pilot", HASH, 901))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orgIdMustMatchTenantId() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org);
        UUID request = UUID.randomUUID();
        UUID otherOrg = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        assertThatThrownBy(() -> jdbc.update("INSERT INTO " + DOMAIN_OPS + " ("
                        + "id, tenant_id, org_id, device_id, operation, state, reason, reason_code, "
                        + "idempotency_key_hash, credential_ref, credential_ref_hash, requested_by, "
                        + "ttl_seconds, requested_at, expires_at, state_updated_at, redacted_result, version) "
                        + "VALUES (?, ?, ?, ?, 'DOMAIN_SECURE_CHANNEL_VERIFY', 'FAILED', 'r', "
                        + "'connector-unavailable', ?, 'vault:domain-ops/pilot', ?, "
                        + "'admin@example.com', 300, ?, ?, ?, '{}'::jsonb, 0)",
                request, org, otherOrg, device, "e".repeat(64), HASH,
                now, Timestamp.from(now.toInstant().plusSeconds(300)), now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID seedDevice(UUID orgId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + DEVICES + " ("
                        + "id, tenant_id, org_id, hostname, machine_fingerprint, "
                        + "status, os_type, os_version, agent_version, "
                        + "created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'ONLINE', 'WINDOWS', "
                        + "'Windows 11', '0.1.0-dev', ?, ?, 0)",
                id, orgId, orgId, "host-" + id, "fp-" + id, now, now);
        return id;
    }

    private UUID insertDomainOps(UUID org,
                                 UUID device,
                                 String operation,
                                 String state,
                                 String credentialRef,
                                 String idempotencyHash,
                                 long ttlSeconds) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + DOMAIN_OPS + " ("
                        + "id, tenant_id, org_id, device_id, operation, state, reason, reason_code, "
                        + "idempotency_key_hash, credential_ref, credential_ref_hash, requested_by, "
                        + "ttl_seconds, requested_at, expires_at, state_updated_at, redacted_result, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'pilot validation', 'connector-unavailable', "
                        + "?, ?, ?, 'admin@example.com', ?, ?, ?, ?, "
                        + "'{\"signal\":\"redacted\"}'::jsonb, 0)",
                id, org, org, device, operation, state, idempotencyHash, credentialRef, HASH,
                ttlSeconds, now, Timestamp.from(now.toInstant().plusSeconds(ttlSeconds)), now);
        return id;
    }
}
