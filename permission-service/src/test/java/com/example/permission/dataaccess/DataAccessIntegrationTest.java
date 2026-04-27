package com.example.permission.dataaccess;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Faz 21.3 PR-F (C3c) + PR-G — end-to-end integration test against a real
 * PostgreSQL (Testcontainers) running the gitops V16/V17/V19/V20/V21/V22
 * schema. PR-G refactor: AccessScopeService no longer calls OpenFGA directly;
 * grant/revoke insert outbox rows in the same TX as the scope row, and the
 * {@link OutboxPoller} consumes them asynchronously.
 *
 * <p>Test surface covers:
 * <ul>
 *   <li>Hibernate {@code validate} of {@link DataAccessScope} and
 *       {@link DataAccessScopeOutboxEntry} against the live schema.</li>
 *   <li>{@link AccessScopeService#grant} happy path — scope + outbox PENDING
 *       row commit atomically; {@link OpenFgaAuthzService} NOT invoked
 *       directly (poller does that).</li>
 *   <li>V19/V21 trigger raising P0001 → {@link AccessScopeException.ScopeValidationException}
 *       (cause-chain extraction unchanged from PR-D iter-1).</li>
 *   <li>{@code uq_scope_active_assignment} partial-UNIQUE.</li>
 *   <li>V19 partial-UNIQUE "safe re-grant" semantic after revoke.</li>
 *   <li>V20 widening: {@code DEPOT} → {@code DEPARTMENT} source_table; encoder
 *       payload {@code warehouse} object type.</li>
 *   <li>{@link OutboxPoller#pollAndProcess} consumes pending entries, calls
 *       {@link OpenFgaAuthzService} via mock, and marks them PROCESSED.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("integration")
@Import(DataAccessIntegrationTest.IntegrationFlywayConfig.class)
@Tag("integration")
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

    @Autowired
    private DataAccessScopeOutboxRepository outboxRepository;

    @Autowired
    private OutboxPoller poller;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    @PersistenceContext(unitName = "reportsDb")
    private EntityManager reportsDbEm;

    @Test
    void contextLoads_hibernateValidatesAgainstRealSchema() {
        assertThat(repository).isNotNull();
        assertThat(outboxRepository).isNotNull();
        assertThat(service).isNotNull();
        assertThat(poller).isNotNull();
        assertThat(reportsDbEm).isNotNull();
    }

    @Test
    void grant_happyPath_persistsScopeAndOutboxButDoesNotCallFgaDirectly() {
        UUID user = UUID.randomUUID();

        AccessScopeService.ScopeMutationResult result = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", null);

        // PG side: scope row persisted, V21 trigger passed.
        DataAccessScope persisted = repository.findById(result.scope().getId()).orElseThrow();
        assertThat(persisted.getUserId()).isEqualTo(user);
        assertThat(persisted.getScopeKind()).isEqualTo(DataAccessScope.ScopeKind.COMPANY);

        // PG side: outbox row PENDING in the same TX.
        DataAccessScopeOutboxEntry outbox = outboxRepository.findById(result.outboxEntry().getId()).orElseThrow();
        assertThat(outbox.getScopeId()).isEqualTo(persisted.getId());
        assertThat(outbox.getAction()).isEqualTo(DataAccessScopeOutboxEntry.Action.GRANT);
        assertThat(outbox.getStatus()).isEqualTo(DataAccessScopeOutboxEntry.Status.PENDING);
        assertThat(outbox.getProcessedAt()).isNull();

        // PR-G contract: NO direct FGA call from the request TX.
        verifyNoInteractions(authzService);
    }

    @Test
    void grant_invalidScopeRef_throwsScopeValidation_andEnqueuesNothing() {
        UUID user = UUID.randomUUID();
        long outboxBefore = outboxRepository.count();

        assertThatThrownBy(() -> service.grant(
                        user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"99999\"]", null))
                .isInstanceOf(AccessScopeException.ScopeValidationException.class);

        assertThat(outboxRepository.count())
                .as("V19/V21 trigger fail must roll back both scope INSERT and outbox INSERT")
                .isEqualTo(outboxBefore);
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

        AccessScopeService.ScopeMutationResult first = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", null);
        AccessScopeService.ScopeMutationResult revokeResult = service.revoke(first.scope().getId(), null);

        assertThat(revokeResult.outboxEntry().getAction())
                .as("revoke must enqueue a REVOKE outbox row")
                .isEqualTo(DataAccessScopeOutboxEntry.Action.REVOKE);

        AccessScopeService.ScopeMutationResult second = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", null);

        assertThat(second.scope().getId()).isNotEqualTo(first.scope().getId());
        assertThat(second.scope().isActive()).isTrue();
    }

    @Test
    void grant_depot_v20DepartmentMappingWorks_payloadHoldsWarehouseTuple() {
        UUID user = UUID.randomUUID();

        AccessScopeService.ScopeMutationResult result = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.DEPOT, "[\"3792\"]", null);

        DataAccessScope scope = result.scope();
        assertThat(scope.getScopeSourceTable()).isEqualTo("DEPARTMENT");
        assertThat(scope.getScopeKind()).isEqualTo(DataAccessScope.ScopeKind.DEPOT);

        DataAccessScopeOutboxEntry outbox = outboxRepository.findById(result.outboxEntry().getId()).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> tuple = (Map<String, Object>) outbox.getPayload().get("tuple");
        assertThat(tuple.get("objectType"))
                .as("DEPOT scope must serialise as warehouse tuple per ADR-0008 § Naming")
                .isEqualTo("warehouse");
        assertThat(tuple.get("objectId")).isEqualTo("wc-department-3792");
    }

    @Test
    void grant_outboxRowInsertedWithCorrectPayload() {
        UUID user = UUID.randomUUID();

        AccessScopeService.ScopeMutationResult result = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", null);

        DataAccessScopeOutboxEntry outbox = outboxRepository.findById(result.outboxEntry().getId()).orElseThrow();
        Map<String, Object> payload = outbox.getPayload();
        assertThat(payload.get("scopeKind")).isEqualTo("COMPANY");
        assertThat(payload.get("scopeRef")).isEqualTo("[\"1001\"]");
        @SuppressWarnings("unchecked")
        Map<String, Object> tuple = (Map<String, Object>) payload.get("tuple");
        assertThat(tuple.get("user")).isEqualTo(user.toString());
        assertThat(tuple.get("relation")).isEqualTo("viewer");
        assertThat(tuple.get("objectType")).isEqualTo("company");
        assertThat(tuple.get("objectId")).isEqualTo("wc-company-1001");
    }

    @Test
    void poller_claimsAndProcessesPendingOutboxEntries_marksProcessed() {
        UUID user = UUID.randomUUID();
        AccessScopeService.ScopeMutationResult granted = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", null);
        Long outboxId = granted.outboxEntry().getId();

        // Drive the poller manually — production runs it on a fixedDelay
        // schedule, but the integration profile sets the interval to 1h so
        // the scheduler does not interfere with the test.
        poller.pollAndProcess();

        verify(authzService).writeTuple(
                eq(user.toString()), eq("viewer"),
                eq("company"), eq("wc-company-1001"));
        DataAccessScopeOutboxEntry processed = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(DataAccessScopeOutboxEntry.Status.PROCESSED);
        assertThat(processed.getProcessedAt()).isNotNull();
        assertThat(processed.getLockedBy()).isNull();
    }
}
