package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EnrollmentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
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

/**
 * #527 CERT_IDENTITY source selection against real PostgreSQL + Flyway schema.
 * The scanner unit test proves redaction and ingest wiring; this test proves
 * the repository queries select only truthful structured source rows.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RolloutFailureCertIdentityRepositoryPostgresIntegrationTest {

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

    private static final Instant NOW = Instant.parse("2026-06-29T08:00:00Z");
    private static final Instant NOT_BEFORE = NOW.minusSeconds(86_400);
    private static final Instant EXPIRED = NOW.minusSeconds(60);
    private static final Instant FUTURE = NOW.plusSeconds(86_400);

    @Autowired
    private EndpointMachineCertRepository certs;

    @Autowired
    private EndpointEnrollmentRepository enrollments;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant;
    private UUID activeExpiredDevice;
    private UUID activeExpiredCert;
    private UUID tpmFailedEnrollment;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();
        activeExpiredDevice = insertDevice("fdq-cert-expired", DeviceStatus.ONLINE);
        UUID futureDevice = insertDevice("fdq-cert-future", DeviceStatus.ONLINE);
        UUID revokedDevice = insertDevice("fdq-cert-revoked", DeviceStatus.ONLINE);
        UUID decommissionedDevice = insertDevice("fdq-cert-decom", DeviceStatus.DECOMMISSIONED);

        activeExpiredCert = insertCert(activeExpiredDevice, "expired", EXPIRED, null);
        insertCert(futureDevice, "future", FUTURE, null);
        insertCert(revokedDevice, "revoked", EXPIRED, NOW.minusSeconds(30));
        insertCert(decommissionedDevice, "decommissioned", EXPIRED, null);

        tpmFailedEnrollment = insertEnrollment("tpm-failed-bound", EnrollmentStatus.TPM_FAILED, activeExpiredDevice);
        insertEnrollment("tpm-failed-deviceless", EnrollmentStatus.TPM_FAILED, null);
        insertEnrollment("tpm-failed-decommissioned", EnrollmentStatus.TPM_FAILED, decommissionedDevice);
        insertEnrollment("consumed-bound", EnrollmentStatus.CONSUMED, activeExpiredDevice);
    }

    @Test
    void expiredActiveCertQueryExcludesFutureRevokedAndDecommissionedDevices() {
        var rows = certs.findExpiredActiveCerts(
                NOW,
                DeviceStatus.DECOMMISSIONED,
                PageRequest.of(0, 10));

        assertThat(rows).extracting("id").containsExactly(activeExpiredCert);
        assertThat(rows.get(0).getDevice().getId()).isEqualTo(activeExpiredDevice);
    }

    @Test
    void tpmFailedEnrollmentQueryExcludesDeviceLessNonFailedAndDecommissionedDevices() {
        var rows = enrollments.findDeviceBoundByStatusExcludingDeviceStatus(
                EnrollmentStatus.TPM_FAILED,
                DeviceStatus.DECOMMISSIONED,
                PageRequest.of(0, 10));

        assertThat(rows).extracting("id").containsExactly(tpmFailedEnrollment);
        assertThat(rows.get(0).getDevice().getId()).isEqualTo(activeExpiredDevice);
    }

    private UUID insertDevice(String hostname, DeviceStatus status) {
        UUID device = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO endpoint_devices
                  (id, tenant_id, hostname, os_type, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'WINDOWS', ?, ?, ?, 0)
                """, device, tenant, hostname,
                status.name(), Timestamp.from(NOW), Timestamp.from(NOW));
        return device;
    }

    private UUID insertCert(UUID device, String label, Instant notAfter, Instant revokedAt) {
        UUID cert = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO endpoint_machine_certs
                  (id, device_id, tenant_id, san_uri, object_guid, channel, cert_serial, cert_thumbprint,
                   cert_issuer, cert_subject, cert_not_before, cert_not_after, machine_fingerprint,
                   enrolled_at, revoked_at, revoked_reason, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'AD_CS', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                cert,
                device,
                tenant,
                "adcomputer:" + label + ":" + UUID.randomUUID(),
                UUID.randomUUID(),
                "serial-" + label,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789" + labelHashSuffix(label),
                "CN=Platform Device Issuer,O=Acik Holding",
                "CN=device-" + label,
                Timestamp.from(NOT_BEFORE),
                Timestamp.from(notAfter),
                "fp-" + label,
                Timestamp.from(NOT_BEFORE),
                revokedAt == null ? null : Timestamp.from(revokedAt),
                revokedAt == null ? null : "test-revoked",
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        return cert;
    }

    private UUID insertEnrollment(String label, EnrollmentStatus status, UUID device) {
        UUID enrollment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO endpoint_enrollments
                  (id, tenant_id, enrollment_token_hash, status, requested_by_subject, device_id,
                   expires_at, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                enrollment,
                tenant,
                "token-" + label + "-" + UUID.randomUUID(),
                status.name(),
                "tester",
                device,
                Timestamp.from(NOW.plusSeconds(3_600)),
                Timestamp.from(NOW),
                Timestamp.from(NOW));
        return enrollment;
    }

    private static String labelHashSuffix(String label) {
        return switch (label) {
            case "expired" -> "abcdef";
            case "future" -> "123456";
            case "revoked" -> "654321";
            default -> "fedcba";
        };
    }
}
