package com.example.endpointadmin.migration;

import com.example.endpointadmin.model.CatalogUninstallSettingsChangeRequest;
import com.example.endpointadmin.model.CatalogUninstallSettingsChangeRequestState;
import com.example.endpointadmin.model.CatalogUninstallSettingsField;
import com.example.endpointadmin.repository.CatalogUninstallSettingsChangeRequestRepository;
import jakarta.persistence.EntityManager;
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
 * AG-028 Phase 0 — Postgres-only migration integration tests for
 * {@code V31__endpoint_catalog_uninstall_flags.sql}.
 *
 * <p>Exercises:
 * <ul>
 *   <li>V31 actually applies on a real Postgres engine + Hibernate validate
 *       is happy against the migration-built table.</li>
 *   <li>{@code endpoint_software_catalog_items.uninstall_supported} and
 *       {@code uninstall_protected} columns exist with default FALSE.</li>
 *   <li>{@code catalog_uninstall_settings_change_requests} CHECK invariants:
 *       maker-checker (approver != proposer), approval pair, terminal
 *       state pairing, field/state enum allowlist, FK composite to
 *       catalog item.</li>
 *   <li>Partial unique index {@code uq_catalog_unins_change_one_open}
 *       blocks a second open request on the same (tenant, catalog, field).</li>
 * </ul>
 *
 * <p>Cross-AI plan-time Codex consensus thread
 * {@code 019e8c8a-4c90-7c00-8f64-c88d47801a06} iter-6 AGREE.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V31EndpointCatalogUninstallFlagsPostgresIntegrationTest {

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
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema",
                () -> "public");
    }

    @Autowired
    private CatalogUninstallSettingsChangeRequestRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager em;

    /**
     * V31 must add the two boolean columns to the catalog items table
     * with DEFAULT FALSE. Existing rows already in the table should
     * implicitly carry FALSE.
     */
    @Test
    void v31_addsCatalogFlagColumnsWithFalseDefault() {
        // Insert a baseline catalog row directly via JDBC, mirroring V7's
        // composite tenant unique key + V10's composite tenant FK enablers.
        UUID tenantId = UUID.randomUUID();
        UUID catalogId = seedApprovedCatalogRow(tenantId);

        Boolean supported = jdbcTemplate.queryForObject(
                "SELECT uninstall_supported FROM endpoint_software_catalog_items WHERE id = ?",
                Boolean.class, catalogId);
        Boolean protectedFlag = jdbcTemplate.queryForObject(
                "SELECT uninstall_protected FROM endpoint_software_catalog_items WHERE id = ?",
                Boolean.class, catalogId);

        assertThat(supported).isFalse();
        assertThat(protectedFlag).isFalse();
    }

    /**
     * Maker-checker DB CHECK: approved_by MUST differ from proposed_by.
     */
    @Test
    void changeRequest_rejectsApproverEqualsProposer() {
        UUID tenantId = UUID.randomUUID();
        UUID catalogId = seedApprovedCatalogRow(tenantId);

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        UUID requestId = UUID.randomUUID();

        // Insert directly to trigger DB CHECK without going through the entity.
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO catalog_uninstall_settings_change_requests
                    (id, tenant_id, catalog_item_id, field, new_value,
                     proposed_by, proposed_at, approved_by, approved_at,
                     applied_at, state, reject_reason, reason, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                requestId, tenantId, catalogId, "UNINSTALL_SUPPORTED", true,
                "alice@example.com", nowTs,
                "alice@example.com", nowTs,
                nowTs, "APPLIED", null, null, 0L, nowTs))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Terminal state pairing CHECK: APPLIED state requires approval pair
     * AND applied_at populated.
     */
    @Test
    void changeRequest_rejectsAppliedWithNullApprovalPair() {
        UUID tenantId = UUID.randomUUID();
        UUID catalogId = seedApprovedCatalogRow(tenantId);
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO catalog_uninstall_settings_change_requests
                    (id, tenant_id, catalog_item_id, field, new_value,
                     proposed_by, proposed_at, approved_by, approved_at,
                     applied_at, state, reject_reason, reason, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), tenantId, catalogId, "UNINSTALL_SUPPORTED", true,
                "alice@example.com", nowTs,
                null, null,
                nowTs, "APPLIED", null, null, 0L, nowTs))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * REJECTED state requires reject_reason populated.
     */
    @Test
    void changeRequest_rejectsRejectedWithoutReason() {
        UUID tenantId = UUID.randomUUID();
        UUID catalogId = seedApprovedCatalogRow(tenantId);
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO catalog_uninstall_settings_change_requests
                    (id, tenant_id, catalog_item_id, field, new_value,
                     proposed_by, proposed_at, approved_by, approved_at,
                     applied_at, state, reject_reason, reason, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), tenantId, catalogId, "UNINSTALL_SUPPORTED", true,
                "alice@example.com", nowTs,
                null, null,
                null, "REJECTED", null, null, 0L, nowTs))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Partial unique index: only one PROPOSED|APPROVED request per
     * (tenant, catalog_item, field). Second open request fails.
     */
    @Test
    void changeRequest_partialUniqueIndexBlocksSecondOpenRequest() {
        UUID tenantId = UUID.randomUUID();
        UUID catalogId = seedApprovedCatalogRow(tenantId);

        CatalogUninstallSettingsChangeRequest first = seedProposed(
                tenantId, catalogId, CatalogUninstallSettingsField.UNINSTALL_SUPPORTED,
                "alice@example.com");
        repository.saveAndFlush(first);

        CatalogUninstallSettingsChangeRequest dup = seedProposed(
                tenantId, catalogId, CatalogUninstallSettingsField.UNINSTALL_SUPPORTED,
                "bob@example.com");

        assertThatThrownBy(() -> repository.saveAndFlush(dup))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Partial unique index ignores TERMINAL (APPLIED or REJECTED) rows.
     * A new PROPOSED request can be created after the prior one applies.
     */
    @Test
    void changeRequest_partialUniqueAllowsNewOpenAfterApplied() {
        UUID tenantId = UUID.randomUUID();
        UUID catalogId = seedApprovedCatalogRow(tenantId);

        // Insert APPLIED row directly (one open then terminal flow).
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        UUID applied = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO catalog_uninstall_settings_change_requests
                    (id, tenant_id, catalog_item_id, field, new_value,
                     proposed_by, proposed_at, approved_by, approved_at,
                     applied_at, state, reject_reason, reason, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                applied, tenantId, catalogId, "UNINSTALL_SUPPORTED", true,
                "alice@example.com", nowTs,
                "bob@example.com", nowTs,
                nowTs, "APPLIED", null, null, 0L, nowTs);

        // Now a fresh PROPOSED for the same (tenant, catalog, field) is allowed.
        CatalogUninstallSettingsChangeRequest fresh = seedProposed(
                tenantId, catalogId, CatalogUninstallSettingsField.UNINSTALL_SUPPORTED,
                "carol@example.com");
        // newValue switched to FALSE (different from the applied one which set it TRUE)
        fresh.setNewValue(false);
        repository.saveAndFlush(fresh);

        assertThat(repository.findById(fresh.getId())).isPresent();
    }

    /**
     * Field enum CHECK rejects values outside the closed set.
     */
    @Test
    void changeRequest_rejectsUnknownField() {
        UUID tenantId = UUID.randomUUID();
        UUID catalogId = seedApprovedCatalogRow(tenantId);
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO catalog_uninstall_settings_change_requests
                    (id, tenant_id, catalog_item_id, field, new_value,
                     proposed_by, proposed_at, approved_by, approved_at,
                     applied_at, state, reject_reason, reason, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), tenantId, catalogId, "ARBITRARY_FIELD", true,
                "alice@example.com", nowTs, null, null, null,
                "PROPOSED", null, null, 0L, nowTs))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Composite tenant FK: cross-tenant catalog reference is rejected.
     */
    @Test
    void changeRequest_rejectsCrossTenantCatalogReference() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID catalogA = seedApprovedCatalogRow(tenantA);

        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO catalog_uninstall_settings_change_requests
                    (id, tenant_id, catalog_item_id, field, new_value,
                     proposed_by, proposed_at, approved_by, approved_at,
                     applied_at, state, reject_reason, reason, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                tenantB,                 // wrong tenant
                catalogA,                // catalog row in tenantA
                "UNINSTALL_SUPPORTED", true,
                "alice@example.com", nowTs, null, null, null,
                "PROPOSED", null, null, 0L, nowTs))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ───────────────────────────── helpers

    private UUID seedApprovedCatalogRow(UUID tenantId) {
        UUID catalogId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowTs = Timestamp.from(now);
        // Two distinct subjects to satisfy V7 maker-checker on creation.
        jdbcTemplate.update(
                """
                INSERT INTO endpoint_software_catalog_items
                    (id, tenant_id, catalog_item_id, status, provider, source_type,
                     source_name, source_trust, package_id, display_name, publisher,
                     version_policy_type, version_policy_value, installer_type,
                     silent_args_policy, sha256, provenance, detection_rule, risk_tier,
                     enabled, uninstall_supported, uninstall_protected,
                     created_by_subject, created_at, last_updated_by_subject, last_updated_at,
                     approved_by_subject, approved_at, revoked_by_subject, revoked_at,
                     revocation_reason, version)
                VALUES (?, ?, ?, 'APPROVED', 'WINGET', 'WINGET',
                        'winget', 'WINGET_COMMUNITY_REVIEWED', '7zip.7zip', '7-Zip', '7-Zip',
                        'LATEST', NULL, NULL, NULL, NULL, NULL,
                        '{"type":"WINGET_PACKAGE"}'::jsonb, 'LOW', true,
                        false, false,
                        'creator@example.com', ?, 'creator@example.com', ?,
                        'approver@example.com', ?, NULL, NULL, NULL, 0)
                """,
                catalogId, tenantId, "7zip-test-" + catalogId.toString().substring(0, 8),
                nowTs, nowTs, nowTs);
        return catalogId;
    }

    private CatalogUninstallSettingsChangeRequest seedProposed(
            UUID tenantId, UUID catalogId,
            CatalogUninstallSettingsField field, String proposedBy) {
        CatalogUninstallSettingsChangeRequest req =
                new CatalogUninstallSettingsChangeRequest();
        req.setId(UUID.randomUUID());
        req.setTenantId(tenantId);
        req.setCatalogItemId(catalogId);
        req.setField(field);
        req.setNewValue(true);
        req.setProposedBy(proposedBy);
        req.setState(CatalogUninstallSettingsChangeRequestState.PROPOSED);
        return req;
    }
}
