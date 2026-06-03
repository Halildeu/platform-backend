package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.EndpointInstallAudit;
import com.example.endpointadmin.model.InstallPostVerification;
import com.example.endpointadmin.model.InstallPreflightDecisionRecorded;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 21.1 PR2b-iv.e-A regression guard —
 * {@link EndpointInstallAuditRepository} per-device + tenant-wide
 * history + audit ownership reads migrated to the canonical
 * effective-org filter (Codex 019e8dde Option B sub-slice e-A AGREE;
 * slice c iter-1 index-friendly form).
 *
 * <p>Canonical predicate:
 * <pre>
 *   WHERE a.tenant_id = :orgId
 *     AND (a.org_id = :orgId OR a.org_id IS NULL)
 *     [+ a.device_id = :deviceId for device-scoped variants]
 *     [+ a.id = :id for ownership gate]
 * </pre>
 *
 * <p>Eight assertions: ownership gate (canonical / legacy NULL /
 * cross-org), device history Page (canonical + legacy NULL +
 * countQuery), tenant-wide history Page (canonical + cross-org),
 * plus a non-migration guard for {@code findByCommandId} (V12
 * {@code command_id UNIQUE} backs per-result uniqueness independent
 * of effective-org).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointInstallAuditEffectiveOrgPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";

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
    private EndpointInstallAuditRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    // ───────────────────────── Ownership gate (Method 1) ─────────────────────────

    @Test
    void ownership_canonicalRow_visibleViaEffectiveOrg() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.canonical");
        UUID commandId = seedCommand(orgA, deviceId, "INSTALL_SOFTWARE", "key-A");
        UUID auditId = persistAuditCanonical(orgA, deviceId, commandId, catalogId);

        Optional<EndpointInstallAudit> hit = repository
                .findVisibleToOrgAndId(orgA, auditId);

        assertThat(hit).isPresent()
                .hasValueSatisfying(a -> assertThat(a.getId()).isEqualTo(auditId));
    }

    @Test
    void ownership_legacyNullRow_visibleViaOrFallback() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.legacy");
        UUID commandId = seedCommand(orgA, deviceId, "INSTALL_SOFTWARE", "key-legacy");
        UUID auditId = persistAuditCanonical(orgA, deviceId, commandId, catalogId);
        makeAuditOrgIdNull(auditId);

        Boolean orgIdIsNull = jdbc.queryForObject(
                "SELECT org_id IS NULL FROM " + SCHEMA
                        + ".endpoint_install_audit WHERE id = ?",
                Boolean.class, auditId);
        assertThat(orgIdIsNull)
                .as("legacy NULL fixture pre-assert: UPDATE org_id=NULL held")
                .isTrue();

        Optional<EndpointInstallAudit> hit = repository
                .findVisibleToOrgAndId(orgA, auditId);

        assertThat(hit)
                .as("legacy NULL row reachable via OR-fallback (ownership gate)")
                .isPresent()
                .hasValueSatisfying(a -> assertThat(a.getId()).isEqualTo(auditId));
    }

    @Test
    void ownership_crossOrg_doesNotLeak() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = seedDevice(orgA);
        UUID catalogA = seedCatalog(orgA, "pkg.A");
        UUID commandA = seedCommand(orgA, deviceA, "INSTALL_SOFTWARE", "key-A");
        UUID auditA = persistAuditCanonical(orgA, deviceA, commandA, catalogA);

        Optional<EndpointInstallAudit> hit = repository
                .findVisibleToOrgAndId(orgB, auditA);

        assertThat(hit).isEmpty();
    }

    // ───────────────────────── Device history page (Method 3) ─────────────────────────

    @Test
    void deviceHistoryPage_canonicalAndLegacyRows_sumOverOrFallback() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.deviceHist");
        UUID commandCanonical = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-canonical");
        UUID commandLegacy = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-legacy");
        UUID auditCanonical = persistAuditCanonical(
                orgA, deviceId, commandCanonical, catalogId);
        UUID auditLegacy = persistAuditCanonical(
                orgA, deviceId, commandLegacy, catalogId);
        makeAuditOrgIdNull(auditLegacy);

        Page<EndpointInstallAudit> page = repository
                .findVisibleToOrgAndDeviceIdOrderByReportedAtDesc(
                        orgA, deviceId, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(EndpointInstallAudit::getId)
                .as("page returns both canonical and legacy NULL rows under same org")
                .containsExactlyInAnyOrder(auditCanonical, auditLegacy);
        assertThat(page.getTotalElements())
                .as("countQuery sibling totals over the OR-fallback predicate")
                .isEqualTo(2L);
    }

    @Test
    void deviceHistoryPage_crossOrg_doesNotLeak() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = seedDevice(orgA);
        UUID catalogA = seedCatalog(orgA, "pkg.A");
        UUID commandA = seedCommand(orgA, deviceA,
                "INSTALL_SOFTWARE", "key-A");
        persistAuditCanonical(orgA, deviceA, commandA, catalogA);

        Page<EndpointInstallAudit> page = repository
                .findVisibleToOrgAndDeviceIdOrderByReportedAtDesc(
                        orgB, deviceA, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(0L);
    }

    // ───────────────────────── Tenant-wide history page (Method 4) ─────────────────────────

    @Test
    void tenantWideHistoryPage_includesMultipleDevicesUnderSameOrg() {
        UUID orgA = UUID.randomUUID();
        UUID device1 = seedDevice(orgA);
        UUID device2 = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.tenantwide");
        UUID command1 = seedCommand(orgA, device1,
                "INSTALL_SOFTWARE", "key-d1");
        UUID command2 = seedCommand(orgA, device2,
                "INSTALL_SOFTWARE", "key-d2");
        UUID audit1 = persistAuditCanonical(orgA, device1, command1, catalogId);
        UUID audit2 = persistAuditCanonical(orgA, device2, command2, catalogId);

        Page<EndpointInstallAudit> page = repository
                .findVisibleToOrgOrderByReportedAtDesc(orgA, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(EndpointInstallAudit::getId)
                .containsExactlyInAnyOrder(audit1, audit2);
        assertThat(page.getTotalElements()).isEqualTo(2L);
    }

    @Test
    void tenantWideHistoryPage_crossOrg_doesNotLeak() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = seedDevice(orgA);
        UUID catalogA = seedCatalog(orgA, "pkg.A");
        UUID commandA = seedCommand(orgA, deviceA,
                "INSTALL_SOFTWARE", "key-A");
        persistAuditCanonical(orgA, deviceA, commandA, catalogA);

        Page<EndpointInstallAudit> page = repository
                .findVisibleToOrgOrderByReportedAtDesc(orgB, PageRequest.of(0, 10));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isEqualTo(0L);
    }

    // ───────────────────────── Non-migration guard: findByCommandId ─────────────────────────

    @Test
    void findByCommandId_remainsTenantAndOrgUnscoped_byDesign() {
        // V12 (command_id UNIQUE) + composite FK to endpoint_commands
        // (id, tenant_id) enforce per-result uniqueness + tenant integrity
        // by themselves. findByCommandId is an idempotency probe (not an
        // admin visibility read path) and stays signature-stable.
        UUID orgA = UUID.randomUUID();
        UUID deviceId = seedDevice(orgA);
        UUID catalogId = seedCatalog(orgA, "pkg.cmdId");
        UUID commandId = seedCommand(orgA, deviceId,
                "INSTALL_SOFTWARE", "key-cmdId");
        UUID auditId = persistAuditCanonical(orgA, deviceId, commandId, catalogId);

        Optional<EndpointInstallAudit> hit = repository.findByCommandId(commandId);

        assertThat(hit).isPresent()
                .hasValueSatisfying(a -> assertThat(a.getId()).isEqualTo(auditId))
                .hasValueSatisfying(a -> assertThat(a.getCommandId()).isEqualTo(commandId));
    }

    // ───────────────────────── Seed helpers ─────────────────────────

    private UUID seedDevice(UUID tenantId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, machine_fingerprint, status, "
                        + " os_type, os_version, agent_version, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, "host-" + id, "fp-" + id, "ONLINE",
                "WINDOWS", "Windows 11", "1.0.0", now, now, 0L);
        return id;
    }

    private UUID seedCatalog(UUID tenantId, String packageId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_catalog_items ("
                        + "id, tenant_id, catalog_item_id, status, provider, source_type,"
                        + " source_name, source_trust, package_id, display_name, publisher,"
                        + " version_policy_type, version_policy_value, installer_type,"
                        + " silent_args_policy, sha256, provenance, detection_rule,"
                        + " risk_tier, enabled, created_by_subject, created_at,"
                        + " last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'APPROVED', 'WINGET', 'WINGET', 'winget',"
                        + " 'WINGET_COMMUNITY_REVIEWED', ?, ?, ?, 'LATEST', NULL,"
                        + " 'WINGET_SILENT', 'DEFAULT', NULL, NULL,"
                        + " '{\"type\":\"WINGET_PACKAGE\"}'::jsonb, 'LOW', true,"
                        + " 'creator', ?, 'creator', ?, 0)",
                id, tenantId, packageId, packageId, "DisplayName-" + packageId,
                "Publisher", now, now);
        return id;
    }

    private UUID seedCommand(UUID tenantId, UUID deviceId, String commandType,
                             String idempotencyKey) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_commands ("
                        + "id, tenant_id, device_id, command_type, idempotency_key, "
                        + " issued_by_subject, issued_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, deviceId, commandType, idempotencyKey,
                "issuer@example.com", now, now, now, 0L);
        return id;
    }

    /**
     * Persist a canonical audit row via the JPA repository — V29 trigger
     * fills org_id from tenant_id, so the row lands canonical
     * (org_id = tenant_id).
     */
    private UUID persistAuditCanonical(UUID tenantId, UUID deviceId,
            UUID commandId, UUID catalogItemId) {
        Instant now = Instant.now();
        EndpointInstallAudit audit = new EndpointInstallAudit();
        audit.setTenantId(tenantId);
        // Faz 21.1 PR2b-iv.e-A — explicit canonical write (PR2b-ii
        // Option A inline) so the JPA path matches the production
        // write contract even if the V29 trigger is bypassed.
        audit.setOrgId(tenantId);
        audit.setDeviceId(deviceId);
        audit.setCommandId(commandId);
        audit.setCatalogItemId(catalogItemId);
        audit.setCatalogPackageId("pkg-" + catalogItemId);
        audit.setCatalogRowVersion(0L);
        audit.setPreflightDecision(InstallPreflightDecisionRecorded.PASS);
        audit.setPreflightDecisionAt(now);
        audit.setActorSubject("actor@example.com");
        audit.setResultStatus(CommandResultStatus.SUCCEEDED);
        audit.setReportedAt(now);
        audit.setPostVerification(InstallPostVerification.SATISFIED);
        EndpointInstallAudit saved = repository.saveAndFlush(audit);
        return saved.getId();
    }

    /**
     * Force the legacy NULL shape on an existing audit row by stripping
     * {@code org_id} via direct UPDATE. The V29 trigger fires on
     * INSERT/UPDATE that targets the {@code org_id} column transitioning
     * from NULL → tenant; UPDATE that sets {@code org_id = NULL}
     * explicitly is the legitimate "row landed pre-PR1" shape — we
     * bypass the trigger by setting it from a non-NULL value back to
     * NULL within the UPDATE itself (the trigger only refills NULLs at
     * INSERT/UPDATE time on the same statement, not retroactively on
     * a single-column SET NULL).
     */
    private void makeAuditOrgIdNull(UUID auditId) {
        jdbc.execute("ALTER TABLE " + SCHEMA
                + ".endpoint_install_audit DISABLE TRIGGER USER");
        try {
            jdbc.update("UPDATE " + SCHEMA
                            + ".endpoint_install_audit SET org_id = NULL WHERE id = ?",
                    auditId);
        } finally {
            jdbc.execute("ALTER TABLE " + SCHEMA
                    + ".endpoint_install_audit ENABLE TRIGGER USER");
        }
    }
}
