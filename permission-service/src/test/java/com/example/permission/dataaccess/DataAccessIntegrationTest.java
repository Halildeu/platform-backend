package com.example.permission.dataaccess;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Faz 21.3 PR-F (C3c) — end-to-end integration test against a real PostgreSQL
 * (Testcontainers) running the gitops V16/V17/V19/V20 schema. Exercises:
 *
 * <ul>
 *   <li>Hibernate {@code validate} of {@link DataAccessScope} against the live
 *       {@code data_access.scope} table — column types / nullability /
 *       JSONB / enum mapping all checked at boot time.</li>
 *   <li>{@link AccessScopeService#grant} happy path round-tripping through
 *       {@code saveAndFlush} and the V19 trigger.</li>
 *   <li>The V19 {@code validate_scope_ref} trigger raising {@code P0001} on
 *       a bogus {@code scope_ref}, and PR-D iter-1 MAJOR-2's structural
 *       extractor mapping it to {@link AccessScopeException.ScopeValidationException}.</li>
 *   <li>The {@code uq_scope_active_assignment} partial-UNIQUE
 *       (active-only) — duplicate active grant blocked, but re-grant after
 *       revoke succeeds.</li>
 *   <li>The V20 widening: {@code DEPOT} → {@code DEPARTMENT} source_table.</li>
 * </ul>
 *
 * <p>The OpenFGA side is mocked ({@link MockitoBean OpenFgaAuthzService});
 * this test focuses on the PG cause-chain and JPA mapping, not OpenFGA's
 * over-the-wire semantics. The dual-DS activation contract gets its own
 * companion class, {@link DataAccessActivationContractTest}.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("integration")
@Import(DataAccessIntegrationTest.IntegrationFlywayConfig.class)
@EnabledIfSystemProperty(
        named = "run-integration-tests",
        matches = "true",
        disabledReason = "Requires Docker (Testcontainers Postgres). "
                + "Enable with: ./mvnw -pl permission-service test -Drun-integration-tests=true"
)
class DataAccessIntegrationTest {

    private static final long ACIK_ORG_ID = 1L;

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("reports_db")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerReportsDbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.reports-db.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.reports-db.username", POSTGRES::getUsername);
        registry.add("spring.datasource.reports-db.password", POSTGRES::getPassword);
    }

    /**
     * Programmatic Flyway bean for the secondary {@code reports_db} DataSource.
     * Spring Boot's auto Flyway is suppressed in {@code application-integration.yml}
     * so it does not try to run the same migrations against the primary H2.
     * Bean's {@code initMethod = "migrate"} guarantees migrations run before
     * any test method observes the EntityManager.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class IntegrationFlywayConfig {
        @Bean(initMethod = "migrate")
        public Flyway reportsDbFlyway(@Qualifier("reportsDbDataSource") DataSource ds) {
            return Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration-reportsdb")
                    .baselineOnMigrate(true)
                    .load();
        }
    }

    @Autowired
    private AccessScopeService service;

    @Autowired
    private DataAccessScopeRepository repository;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    @PersistenceContext(unitName = "reportsDb")
    private EntityManager reportsDbEm;

    @Test
    void contextLoads_hibernateValidatesAgainstRealSchema() {
        // Boot-time signal: if Hibernate's @Entity ↔ data_access.scope mapping
        // disagrees with the live schema (column types, nullability, JSONB,
        // enum), context startup fails and this test never reaches its body.
        assertThat(repository).isNotNull();
        assertThat(service).isNotNull();
        assertThat(reportsDbEm).isNotNull();
    }

    @Test
    void grant_happyPath_persistsToRealPgAndCallsTupleWriter() {
        UUID user = UUID.randomUUID();

        DataAccessScope result = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", null);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getScopeKind()).isEqualTo(DataAccessScope.ScopeKind.COMPANY);
        assertThat(result.getScopeRef()).isEqualTo("[\"1001\"]");
        assertThat(result.getScopeSourceTable()).isEqualTo("COMPANY");
        assertThat(result.getGrantedAt()).isNotNull();
        assertThat(result.isActive()).isTrue();

        // Round-trip via repository — confirms the row really hit PG (not just
        // the L1 cache) and Hibernate's converter unmarshals scope_kind back.
        DataAccessScope persisted = repository.findById(result.getId()).orElseThrow();
        assertThat(persisted.getUserId()).isEqualTo(user);
        assertThat(persisted.getScopeKind()).isEqualTo(DataAccessScope.ScopeKind.COMPANY);

        verify(authzService).writeTuple(
                eq(user.toString()), eq("viewer"), eq("company"), eq("wc-company-1001"));
    }

    @Test
    void grant_invalidScopeRef_throwsScopeValidation_viaPgTriggerCauseChain() {
        UUID user = UUID.randomUUID();

        // scope_ref '99999' is NOT in workcube_mikrolink.company → V19's
        // BEFORE-INSERT trigger validate_scope_ref() raises P0001.
        // PR-D iter-1 MAJOR-2's structural extractor must map that to
        // ScopeValidationException, not propagate the raw DataIntegrityViolation.
        assertThatThrownBy(() -> service.grant(
                        user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"99999\"]", null))
                .isInstanceOf(AccessScopeException.ScopeValidationException.class);

        // Service must NOT reach the tuple writer when the PG INSERT failed.
        verifyNoInteractions(authzService);
    }

    @Test
    void grant_duplicateActive_throwsScopeAlreadyGranted_viaPgUniqueConstraint() {
        UUID user = UUID.randomUUID();
        service.grant(user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", null);

        assertThatThrownBy(() -> service.grant(
                        user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", null))
                .isInstanceOf(AccessScopeException.ScopeAlreadyGrantedException.class);
    }

    @Test
    void revokeAndReGrant_succeeds_partialUniqueAllowsAfterRevoke() {
        UUID user = UUID.randomUUID();

        DataAccessScope first = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", null);
        service.revoke(first.getId(), null);

        // V19 defines uq_scope_active_assignment as a partial UNIQUE
        // (WHERE revoked_at IS NULL). After revoking the first row, the
        // same triple must be insertable again — that is the whole point
        // of "safe re-grant" Codex iter-2 absorbed during V19 review.
        DataAccessScope second = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", null);

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.isActive()).isTrue();
    }

    @Test
    void grant_depot_v20DepartmentMappingWorks_endToEnd() {
        UUID user = UUID.randomUUID();

        DataAccessScope result = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.DEPOT, "[\"3792\"]", null);

        // V20 widens depot → DEPARTMENT in both the CHECK and the trigger;
        // service.grant maps DEPOT → "DEPARTMENT" source_table; encoder
        // renames depot → warehouse for OpenFGA. End-to-end this is the
        // critical naming bridge.
        assertThat(result.getScopeSourceTable()).isEqualTo("DEPARTMENT");
        assertThat(result.getScopeKind()).isEqualTo(DataAccessScope.ScopeKind.DEPOT);

        verify(authzService).writeTuple(
                eq(user.toString()), eq("viewer"),
                eq("warehouse"), eq("wc-department-3792"));
    }
}
