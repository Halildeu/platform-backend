package com.example.endpointadmin.migration;

import com.example.endpointadmin.repository.EndpointInstallAuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-021 — V12 migration sanity test (Codex 019e6dfb iter-3 P2-2).
 *
 * <p>Verifies that Hibernate {@code validate} mode is happy against the
 * {@link com.example.endpointadmin.model.EndpointInstallAudit} mapping
 * and the {@link EndpointInstallAuditRepository} wires. Persist round-trip
 * tests live in {@code EndpointInstallAuditPostgresIntegrationTest}
 * (Testcontainers PG, Flyway-applied schema) — the H2-backed
 * {@code @DataJpaTest} slice shares an in-memory instance across test
 * classes ({@code DB_CLOSE_DELAY=-1}) and the cross-class lifecycle is
 * documented in {@code EndpointSoftwareCatalogMigrationTest} as a known
 * flake source, so we keep the slice scope strictly to mapping +
 * wire-up validation here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class EndpointInstallAuditMigrationTest {

    @Autowired
    private EndpointInstallAuditRepository installAuditRepository;

    @Test
    void v12ContextStartsAndInstallAuditRepositoryWires() {
        // If Hibernate validate-mode disagreed with the V12 layout
        // generated from EndpointInstallAudit, the application-test
        // context would have failed to start. Reaching this assertion
        // proves the entity ↔ schema contract.
        assertThat(installAuditRepository).isNotNull();
    }
}
