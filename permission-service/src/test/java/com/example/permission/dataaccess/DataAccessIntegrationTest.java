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
import java.time.Instant;
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
 * Faz 21.3 PR-F (C3c) + PR-G + V25/V26 — end-to-end integration test against
 * a real PostgreSQL (Testcontainers) running the gitops
 * V16/V17/V19/V20/V21/V22/V23/V25/V26 schema. PR-G refactor: AccessScopeService
 * no longer calls OpenFGA directly; grant/revoke insert outbox rows in the
 * same TX as the scope row, and the {@link OutboxPoller} consumes them
 * asynchronously.
 *
 * <p>V25 (Codex 019dd34e hybrid contract) flips the COMPANY anchor from
 * the workcube_mikrolink.COMPANY directory (80,246-row, tenant-blind) to
 * workcube_mikrolink.OUR_COMPANY (Workcube tenant table, COMP_ID PK):
 * scope_kind=company is paired with scope_source_table=OUR_COMPANY by the
 * V25 CHECK constraint, validate_scope_ref() is widened to a 4-arg signature
 * (p_org_id) with tenant predicates per scope_kind, and the encoder emits
 * {@code wc-our-company-<COMP_ID>} object ids. V26 then adds dual-format
 * tolerance (raw '1' vs canonical '["1"]') to validate_scope_ref so live
 * ETL canonical JSON form joins correctly against fixtures.
 *
 * <p>Test surface covers:
 * <ul>
 *   <li>Hibernate {@code validate} of {@link DataAccessScope} and
 *       {@link DataAccessScopeOutboxEntry} against the live schema.</li>
 *   <li>{@link AccessScopeService#grant} happy path — scope + outbox PENDING
 *       row commit atomically; {@link OpenFgaAuthzService} NOT invoked
 *       directly (poller does that).</li>
 *   <li>V19/V21/V25 trigger raising P0001 → {@link AccessScopeException.ScopeValidationException}
 *       (cause-chain extraction unchanged from PR-D iter-1; V25 adds
 *       tenant-predicate failures alongside the lineage failures).</li>
 *   <li>{@code uq_scope_active_assignment} partial-UNIQUE.</li>
 *   <li>V19 partial-UNIQUE "safe re-grant" semantic after revoke.</li>
 *   <li>V20 widening: {@code DEPOT} → {@code DEPARTMENT} source_table; encoder
 *       payload {@code warehouse} object type.</li>
 *   <li>V25 CHECK constraint guard: legacy {@code scope_kind=company +
 *       scope_source_table=COMPANY} pair must be rejected at write time
 *       even when the trigger predicate would have passed.</li>
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
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", null);

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

        // V25: scope_ref="[\"99\"]" points to a non-existent OUR_COMPANY.COMP_ID
        // → tenant predicate finds zero rows → P0001 from validate_scope_ref().
        assertThatThrownBy(() -> service.grant(
                        user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"99\"]", null))
                .isInstanceOf(AccessScopeException.ScopeValidationException.class);

        assertThat(outboxRepository.count())
                .as("V19/V21 trigger fail must roll back both scope INSERT and outbox INSERT")
                .isEqualTo(outboxBefore);
        verifyNoInteractions(authzService);
    }

    @Test
    void grant_duplicateActive_throwsScopeAlreadyGranted_viaPgUniqueConstraint() {
        UUID user = UUID.randomUUID();
        service.grant(user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", null);

        assertThatThrownBy(() -> service.grant(
                        user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", null))
                .isInstanceOf(AccessScopeException.ScopeAlreadyGrantedException.class);
    }

    @Test
    void revokeAndReGrant_succeeds_partialUniqueAllowsAfterRevoke() {
        UUID user = UUID.randomUUID();

        AccessScopeService.ScopeMutationResult first = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", null);
        AccessScopeService.ScopeMutationResult revokeResult = service.revoke(first.scope().getId(), null);

        assertThat(revokeResult.outboxEntry().getAction())
                .as("revoke must enqueue a REVOKE outbox row")
                .isEqualTo(DataAccessScopeOutboxEntry.Action.REVOKE);

        AccessScopeService.ScopeMutationResult second = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", null);

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
    void grant_outboxRowInsertedWithCorrectPayload_andTypedTupleColumns() {
        UUID user = UUID.randomUUID();

        AccessScopeService.ScopeMutationResult result = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", null);

        DataAccessScopeOutboxEntry outbox = outboxRepository.findById(result.outboxEntry().getId()).orElseThrow();

        // V23 typed columns survive the round-trip through PG.
        // V25 object id namespace: company:wc-our-company-<COMP_ID>.
        assertThat(outbox.getTupleUser()).isEqualTo("user:" + user);
        assertThat(outbox.getTupleRelation()).isEqualTo("viewer");
        assertThat(outbox.getTupleObject()).isEqualTo("company:wc-our-company-1");

        // JSONB payload mirrors the typed columns and keeps objectType/Id split
        // for V22-era clients that index on those keys.
        Map<String, Object> payload = outbox.getPayload();
        assertThat(payload.get("scopeKind")).isEqualTo("COMPANY");
        assertThat(payload.get("scopeRef")).isEqualTo("[\"1\"]");
        @SuppressWarnings("unchecked")
        Map<String, Object> tuple = (Map<String, Object>) payload.get("tuple");
        assertThat(tuple.get("user")).isEqualTo("user:" + user);
        assertThat(tuple.get("relation")).isEqualTo("viewer");
        assertThat(tuple.get("object")).isEqualTo("company:wc-our-company-1");
        assertThat(tuple.get("objectType")).isEqualTo("company");
        assertThat(tuple.get("objectId")).isEqualTo("wc-our-company-1");
    }

    /**
     * Codex 019dd0e0 iter-2 MINOR — same-tuple ordering across scope_id
     * boundaries. V19's {@code uq_scope_active_assignment} is a partial UNIQUE
     * (active-only), so revoking and re-granting the same tuple produces a
     * fresh {@code data_access.scope.id}. The V22 ordering guard keyed on
     * {@code scope_id} would let the GRANT outbox row (different scope_id) be
     * claimed BEFORE the REVOKE row → final FGA state "tuple deleted" while
     * the canonical scope row is ACTIVE — invariant break. V23's tuple-typed
     * ordering index (Codex 019dd0e0 BLOCKER 2) fixes that: claimBatch must
     * only return the older REVOKE row first.
     */
    @Test
    void revokeThenRegrant_sameTuple_orderingPreservedByTupleKey() {
        UUID user = UUID.randomUUID();

        // 1. Grant scope A.
        AccessScopeService.ScopeMutationResult grantA = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", null);

        // 2. Process scope A's outbox row to PROCESSED so it does not block
        //    the new same-tuple ordering check (only PENDING/PROCESSING rows
        //    participate in the partial index per V23).
        poller.pollAndProcess();
        DataAccessScopeOutboxEntry afterFirstPoll = outboxRepository
                .findById(grantA.outboxEntry().getId()).orElseThrow();
        assertThat(afterFirstPoll.getStatus())
                .isEqualTo(DataAccessScopeOutboxEntry.Status.PROCESSED);

        // 3. Revoke scope A → REVOKE PENDING.
        AccessScopeService.ScopeMutationResult revokeA = service.revoke(
                grantA.scope().getId(), null);
        assertThat(revokeA.outboxEntry().getAction())
                .isEqualTo(DataAccessScopeOutboxEntry.Action.REVOKE);

        // 4. Re-grant — V19 partial UNIQUE allows it; new scope.id, but same
        //    FGA tuple coordinates.
        AccessScopeService.ScopeMutationResult grantB = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", null);
        assertThat(grantB.scope().getId()).isNotEqualTo(grantA.scope().getId());

        // 5. Two PENDING rows now exist for the SAME tuple (different scope_id).
        //    The V23 tuple-key NOT EXISTS guard must serialise them: claim
        //    only returns the older REVOKE row.
        Instant lockUntil = Instant.now().plusSeconds(120);
        List<DataAccessScopeOutboxEntry> claimed = outboxRepository.claimBatch(
                "ordering-test-poller", lockUntil, 25);

        assertThat(claimed)
                .as("V23 tuple-key ordering must serialise REVOKE before GRANT for the same tuple")
                .hasSize(1);
        DataAccessScopeOutboxEntry first = claimed.get(0);
        assertThat(first.getAction()).isEqualTo(DataAccessScopeOutboxEntry.Action.REVOKE);
        assertThat(first.getScopeId()).isEqualTo(grantA.scope().getId());
        assertThat(first.getTupleObject()).isEqualTo("company:wc-our-company-1");
    }

    @Test
    void poller_claimsAndProcessesPendingOutboxEntries_marksProcessed() {
        UUID user = UUID.randomUUID();
        AccessScopeService.ScopeMutationResult granted = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", null);
        Long outboxId = granted.outboxEntry().getId();

        // Drive the poller manually — production runs it on a fixedDelay
        // schedule, but the integration profile sets the interval to 1h so
        // the scheduler does not interfere with the test.
        poller.pollAndProcess();

        verify(authzService).writeTuple(
                eq(user.toString()), eq("viewer"),
                eq("company"), eq("wc-our-company-1"));
        DataAccessScopeOutboxEntry processed = outboxRepository.findById(outboxId).orElseThrow();
        assertThat(processed.getStatus()).isEqualTo(DataAccessScopeOutboxEntry.Status.PROCESSED);
        assertThat(processed.getProcessedAt()).isNotNull();
        assertThat(processed.getLockedBy()).isNull();
    }

    /**
     * V25 layered rejection of the legacy V19/V20 pairing
     * {@code (scope_kind='company', scope_source_table='COMPANY')}. PG's
     * BEFORE-INSERT trigger {@code scope_validate_trg()} runs ahead of
     * row-level CHECK constraints — the trigger's validate_scope_ref()
     * has no IF branch for the legacy pair (the V25 four-way IF/ELSIF
     * matrix only accepts the four V25 source_table values) and falls
     * through to {@code RETURN FALSE}, so the trigger raises P0001
     * first; the {@code scope_kind_source_table_consistent} CHECK is
     * defence-in-depth behind it. Either layer rejects — what matters
     * for the V25 contract is that the legacy pair is unreachable.
     * Service callers can't hit this state because {@link AccessScopeService}
     * hardcodes {@code OUR_COMPANY}; the test bypasses the service via
     * raw SQL to prove the DB layer enforces the V25 boundary.
     */
    @Test
    void v25_rejectsLegacyCompanySourceTablePair_atTriggerOrCheck() {
        UUID user = UUID.randomUUID();
        // hasStackTraceContaining walks the full cause chain — accept either
        // the trigger's P0001 rejection (validate_scope_ref / scope_validate_trg)
        // or the CHECK constraint name as the rejection signal.
        assertThatThrownBy(() -> reportsDbEm.createNativeQuery(
                "INSERT INTO data_access.scope " +
                "  (user_id, org_id, scope_kind, scope_source_schema, scope_source_table, scope_ref) " +
                "VALUES (?, ?, 'company', 'workcube_mikrolink', 'COMPANY', '[\"1\"]')")
                .setParameter(1, user)
                .setParameter(2, ACIK_ORG_ID)
                .executeUpdate())
                .as("V25 must reject legacy company/COMPANY pair (trigger-first or CHECK-fallback)")
                .hasStackTraceContaining("scope_validate_trg");
    }

    /**
     * V25 tenant-aware predicate guard. With OUR_COMPANY anchor in place,
     * a scope_ref pointing to a non-existent {@code OUR_COMPANY.COMP_ID}
     * (or one not mapped to {@code p_org_id} via
     * {@code data_access.organization_company}) must fail the trigger
     * predicate — the V19/V20/V21 versions only checked existence in the
     * 80,246-row directory and would have let this through.
     */
    @Test
    void v25_tenantPredicate_rejectsScopeRefWithoutOrgMapping() {
        UUID user = UUID.randomUUID();
        // OUR_COMPANY row 99 is not seeded by V90 → tenant predicate
        // count(*)=0 → trigger raises P0001.
        assertThatThrownBy(() -> service.grant(
                        user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"99\"]", null))
                .isInstanceOf(AccessScopeException.ScopeValidationException.class)
                .hasMessageContaining("lineage guard");
    }

    /**
     * V26 dual-format compatibility guard. Live ETL emits canonical JSON
     * source_pk ({@code '["1"]'}); test fixtures may use raw form ({@code '1'}).
     * V26's {@code (source_pk = v_pk OR source_pk = p_ref)} predicate must
     * accept both. V90 seeds OUR_COMPANY with the canonical form
     * (scope_ref="[\"1\"]" → v_pk="1", p_ref="[\"1\"]") — the canonical OR
     * branch must match.
     */
    @Test
    void v26_canonicalJsonSourcePk_acceptedByValidator() {
        UUID user = UUID.randomUUID();

        AccessScopeService.ScopeMutationResult result = service.grant(
                user, ACIK_ORG_ID, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", null);

        // The grant succeeded => V25 trigger + V26 dual-format predicate
        // resolved (oc.source_pk='["1"]') = (p_ref='["1"]'). Encoder output
        // is the V25 canonical FGA object id.
        assertThat(result.scope().getScopeSourceTable()).isEqualTo("OUR_COMPANY");
        assertThat(result.outboxEntry().getTupleObject())
                .isEqualTo("company:wc-our-company-1");
    }
}
