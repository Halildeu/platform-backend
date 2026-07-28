package com.example.budget;

import static com.example.budget.api.ProjectActualDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.budget.security.BudgetActor;
import com.example.budget.service.ProjectActualProviderClient;
import com.example.budget.service.ProjectActualService;
import com.example.budget.service.TenantDatabaseScope;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ProjectActualServicePostgresIntegrationTest {
    private static final String SCHEMA = "budget_service";
    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("budget_actuals")
                    .withUsername("budget_migrator")
                    .withPassword("synthetic-migration-test-password");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static StubProvider provider;
    private static ProjectActualService service;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .table("budget_flyway_history")
                .locations("classpath:db/migration", "classpath:db/vendor/postgresql")
                .load()
                .migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setConnectionInitSql("SET search_path TO " + SCHEMA + ", public");
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        provider = new StubProvider();
        service = new ProjectActualService(
                jdbc,
                new TenantDatabaseScope(jdbc, true),
                provider,
                new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void resetProvider() {
        provider.rows.set(List.of());
        provider.failure.set(null);
    }

    @Test
    void idc1SyncIsIdempotentVersionedClassifiedAndReconciled() {
        BudgetActor actor = new BudgetActor(
                "tenant-idc1", 35L, "cost-controller", Set.of(44200L), false);
        ProjectBindingView binding = inTransaction(() -> service.createBinding(
                actor,
                new CreateProjectBindingRequest(
                        "platform-project-idc1",
                        "WORKCUBE",
                        35L,
                        44200L,
                        "44200")));
        inTransaction(() -> service.replaceAndActivateRules(
                actor,
                new ReplaceCostRulesRequest(List.of(
                        new CostRuleInput(
                                10, "740", "INCLUDE_COST", "INCLUDE_NEGATIVE_COST", null),
                        new CostRuleInput(
                                20, "320", "EXCLUDE_COUNTERPART", "EXCLUDE_COUNTERPART", null)))));

        provider.rows.set(List.of(
                row(1, "740.01", "DEBIT", "1000.00", "INVOICE", false),
                row(2, "740.01", "CREDIT", "-100.00", "INVOICE", false),
                row(3, "320.01", "DEBIT", "1000.00", "INVOICE", false),
                row(4, "102.01", "DEBIT", "500.00", "TRANSFER", false),
                row(5, "999.01", "DEBIT", "50.00", "OTHER", false)));

        ProjectActualSyncResult first = service.sync(
                actor,
                binding.id(),
                new ProjectActualSyncRequest(FROM, TO),
                "Bearer synthetic-test-token");
        assertThat(first.status()).isEqualTo("MATCHED");
        assertThat(first.changedRowCount()).isEqualTo(5);
        assertThat(first.tombstoneRowCount()).isZero();
        assertThat(first.sourceAmount()).isEqualByComparingTo("2450.00");
        assertThat(inTransaction(() -> jdbc.queryForObject("""
                SELECT verified_at IS NOT NULL
                  FROM budget_project_bindings
                 WHERE id=?
                """, Boolean.class, binding.id()))).isTrue();

        ProjectActualSummary firstSummary = inTransaction(() -> service.summary(
                actor, binding.id(), FROM, TO));
        assertThat(firstSummary.accountingActual()).isEqualByComparingTo("2450.00");
        assertThat(firstSummary.classifiedCost()).isEqualByComparingTo("900.00");
        assertThat(firstSummary.excludedAmount()).isEqualByComparingTo("1500.00");
        assertThat(firstSummary.requiresReviewAmount()).isEqualByComparingTo("50.00");
        assertThat(firstSummary.rowCount()).isEqualTo(5);
        assertThat(firstSummary.snapshotRowCount()).isEqualTo(5);
        assertThat(firstSummary.requiresReviewCount()).isEqualTo(1);
        assertThat(firstSummary.reconciliationStatus()).isEqualTo("MATCHED");

        ProjectActualSyncResult unchanged = service.sync(
                actor,
                binding.id(),
                new ProjectActualSyncRequest(FROM, TO),
                "Bearer synthetic-test-token");
        assertThat(unchanged.changedRowCount()).isZero();
        assertThat(unchanged.tombstoneRowCount()).isZero();
        assertThat(count("actual_snapshot_versions", actor.tenantId())).isEqualTo(5);

        inTransaction(() -> jdbc.update("""
                UPDATE actual_snapshots
                   SET normalized_amount=999
                 WHERE tenant_id=? AND project_binding_id=? AND journal_row_id=1
                """, actor.tenantId(), binding.id()));
        ProjectActualSyncResult driftDetected = service.sync(
                actor,
                binding.id(),
                new ProjectActualSyncRequest(FROM, TO),
                "Bearer synthetic-test-token");
        assertThat(driftDetected.status()).isEqualTo("DIFFERENCE");
        assertThat(driftDetected.differenceAmount()).isEqualByComparingTo("1.00");

        provider.rows.set(List.of(
                row(1, "740.01", "DEBIT", "1100.00", "INVOICE", false),
                row(2, "740.01", "CREDIT", "-100.00", "INVOICE", false),
                row(4, "102.01", "DEBIT", "500.00", "TRANSFER", false),
                row(5, "999.01", "DEBIT", "50.00", "OTHER", false)));
        ProjectActualSyncResult changed = service.sync(
                actor,
                binding.id(),
                new ProjectActualSyncRequest(FROM, TO),
                "Bearer synthetic-test-token");
        assertThat(changed.status()).isEqualTo("MATCHED");
        assertThat(changed.changedRowCount()).isEqualTo(1);
        assertThat(changed.tombstoneRowCount()).isEqualTo(1);
        assertThat(changed.sourceAmount()).isEqualByComparingTo("1550.00");
        assertThat(changed.snapshotAmount()).isEqualByComparingTo("1550.00");
        assertThat(count("actual_snapshot_versions", actor.tenantId())).isEqualTo(7);

        ProjectActualSummary changedSummary = inTransaction(() -> service.summary(
                actor, binding.id(), FROM, TO));
        assertThat(changedSummary.accountingActual()).isEqualByComparingTo("1550.00");
        assertThat(changedSummary.classifiedCost()).isEqualByComparingTo("1000.00");
        assertThat(changedSummary.excludedAmount()).isEqualByComparingTo("500.00");
        assertThat(changedSummary.requiresReviewAmount()).isEqualByComparingTo("50.00");

        provider.rows.set(List.of(
                row(1, "740.01", "DEBIT", "1100.00", "INVOICE", true),
                row(2, "740.01", "CREDIT", "-100.00", "INVOICE", false),
                row(4, "102.01", "DEBIT", "500.00", "TRANSFER", false),
                row(5, "999.01", "DEBIT", "50.00", "OTHER", false)));
        ProjectActualSyncResult cancelled = service.sync(
                actor,
                binding.id(),
                new ProjectActualSyncRequest(FROM, TO),
                "Bearer synthetic-test-token");
        assertThat(cancelled.status()).isEqualTo("MATCHED");
        assertThat(cancelled.changedRowCount()).isEqualTo(1);
        assertThat(cancelled.tombstoneRowCount()).isZero();
        assertThat(cancelled.sourceAmount()).isEqualByComparingTo("450.00");
        assertThat(count("actual_snapshot_versions", actor.tenantId())).isEqualTo(8);

        ProjectActualSummary cancelledSummary = inTransaction(() -> service.summary(
                actor, binding.id(), FROM, TO));
        assertThat(cancelledSummary.accountingActual()).isEqualByComparingTo("450.00");
        assertThat(cancelledSummary.classifiedCost()).isEqualByComparingTo("-100.00");
        assertThat(cancelledSummary.excludedAmount()).isEqualByComparingTo("500.00");
        assertThat(cancelledSummary.requiresReviewAmount()).isEqualByComparingTo("50.00");
        assertThat(cancelledSummary.rowCount()).isEqualTo(3);
        assertThat(cancelledSummary.snapshotRowCount()).isEqualTo(5);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE actual_snapshot_versions SET normalized_amount=0"))
                .hasMessageContaining("append-only");
    }

    @Test
    void projectOutsideAuthoritativeScopeFailsBeforeBindingWrite() {
        BudgetActor actor = new BudgetActor(
                "tenant-denied", 35L, "reader", Set.of(99L), false);
        assertThatThrownBy(() -> inTransaction(() -> service.createBinding(
                actor,
                new CreateProjectBindingRequest(
                        "platform-project-idc1",
                        "WORKCUBE",
                        35L,
                        44200L,
                        "44200"))))
                .hasMessageContaining("outside the authoritative scope");
        assertThat(count("budget_project_bindings", actor.tenantId())).isZero();
    }

    @Test
    void readOnlyActorCanFindExistingBindingWithoutCreatingAnotherOne() {
        BudgetActor writer = new BudgetActor(
                "tenant-binding-read", 35L, "cost-controller", Set.of(44200L), false);
        ProjectBindingView created = inTransaction(() -> service.createBinding(
                writer,
                new CreateProjectBindingRequest(
                        "workcube:35:44200",
                        "WORKCUBE",
                        35L,
                        44200L,
                        "IDC1")));

        BudgetActor reader = new BudgetActor(
                "tenant-binding-read", 35L, "budget-reader", Set.of(44200L), false);
        ProjectBindingView found = inTransaction(() ->
                service.findBinding(reader, "workcube", 44200L));

        assertThat(found.id()).isEqualTo(created.id());
        assertThat(found.platformProjectRef()).isEqualTo("workcube:35:44200");
        assertThat(count("budget_project_bindings", reader.tenantId())).isEqualTo(1);

        assertThatThrownBy(() -> inTransaction(() ->
                service.findBinding(
                        new BudgetActor(
                                "tenant-binding-read",
                                35L,
                                "wrong-project-reader",
                                Set.of(99L),
                                false),
                        "WORKCUBE",
                        44200L)))
                .hasMessageContaining("outside the authoritative scope");
    }

    @Test
    void providerLedgerYearMustMatchPostingYear() {
        BudgetActor actor = new BudgetActor(
                "tenant-integrity", 35L, "cost-controller", Set.of(44200L), false);
        ProjectBindingView binding = inTransaction(() -> service.createBinding(
                actor,
                new CreateProjectBindingRequest(
                        "platform-project-idc1",
                        "WORKCUBE",
                        35L,
                        44200L,
                        "44200")));
        ProviderActualRow valid = row(
                1, "740.01", "DEBIT", "100.00", "INVOICE", false);
        ProviderActualRow wrongLedgerYear = withHash(new ProviderActualRow(
                valid.sourceSystem(),
                2025,
                valid.sourceCompanyId(),
                valid.sourceProjectId(),
                valid.journalCardId(),
                valid.journalRowId(),
                valid.postingDate(),
                valid.accountCode(),
                valid.debitCredit(),
                valid.signedAmount(),
                valid.currency(),
                valid.actionType(),
                valid.actionId(),
                valid.documentType(),
                valid.documentNo(),
                valid.resolutionStatus(),
                valid.cancelled(),
                null));
        provider.rows.set(List.of(wrongLedgerYear));

        ProjectActualSyncResult blocked = service.sync(
                actor,
                binding.id(),
                new ProjectActualSyncRequest(FROM, TO),
                "Bearer synthetic-test-token");
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.failureCode()).isEqualTo("PROVIDER_DATA_INVALID");
        assertThat(count("actual_snapshots", actor.tenantId())).isZero();
        assertThat(count("actual_sync_batches", actor.tenantId())).isEqualTo(1);
        assertThat(inTransaction(() -> jdbc.queryForObject("""
                SELECT failure_code
                  FROM actual_sync_batches
                 WHERE tenant_id=?
                """, String.class, actor.tenantId())))
                .isEqualTo("PROVIDER_DATA_INVALID");
    }

    @Test
    void providerAuthenticationFailureIsNotMaskedAsAvailabilityFailure() {
        BudgetActor actor = new BudgetActor(
                "tenant-provider-auth", 35L, "1204", Set.of(44200L), false);
        ProjectBindingView binding = inTransaction(() -> service.createBinding(
                actor,
                new CreateProjectBindingRequest(
                        "platform-project-idc1",
                        "WORKCUBE",
                        35L,
                        44200L,
                        "44200")));
        provider.failure.set(HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED,
                "rejected",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8));

        ProjectActualSyncResult blocked = service.sync(
                actor,
                binding.id(),
                new ProjectActualSyncRequest(FROM, TO),
                "Bearer synthetic-test-token");

        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.failureCode()).isEqualTo("PROVIDER_TOKEN_REJECTED");
        assertThat(count("actual_snapshots", actor.tenantId())).isZero();
        assertThat(count("actual_sync_batches", actor.tenantId())).isEqualTo(1);
    }

    @Test
    void sourceAccountingRowCannotMoveBetweenProjectBindings() {
        BudgetActor actor = new BudgetActor(
                "tenant-source-grain", 35L, "1204", Set.of(44200L, 44201L), false);
        ProjectBindingView firstBinding = inTransaction(() -> service.createBinding(
                actor,
                new CreateProjectBindingRequest(
                        "platform-project-idc1",
                        "WORKCUBE",
                        35L,
                        44200L,
                        "44200")));
        ProjectBindingView secondBinding = inTransaction(() -> service.createBinding(
                actor,
                new CreateProjectBindingRequest(
                        "platform-project-idc2",
                        "WORKCUBE",
                        35L,
                        44201L,
                        "44201")));
        provider.rows.set(List.of(row(1, "740.01", "DEBIT", "100.00", "INVOICE", false)));
        assertThat(service.sync(
                actor,
                firstBinding.id(),
                new ProjectActualSyncRequest(FROM, TO),
                "Bearer synthetic-test-token").status()).isEqualTo("MATCHED");

        ProviderActualRow secondProjectRow = rowForProject(
                44201L, 1, "740.01", "DEBIT", "100.00", "INVOICE", false);
        provider.rows.set(List.of(secondProjectRow));
        ProjectActualSyncResult blocked = service.sync(
                actor,
                secondBinding.id(),
                new ProjectActualSyncRequest(FROM, TO),
                "Bearer synthetic-test-token");

        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.failureCode()).isEqualTo("SOURCE_GRAIN_CONFLICT");
        assertThat(inTransaction(() -> jdbc.queryForObject("""
                SELECT project_binding_id
                  FROM actual_snapshots
                 WHERE tenant_id=? AND journal_row_id=1
                """, UUID.class, actor.tenantId())))
                .isEqualTo(firstBinding.id());
    }

    private static ProviderActualRow row(
            long rowId,
            String accountCode,
            String debitCredit,
            String amount,
            String documentType,
            boolean cancelled) {
        ProviderActualRow unhashed = new ProviderActualRow(
                "WORKCUBE",
                2026,
                35L,
                44200L,
                9000L,
                rowId,
                LocalDate.of(2026, 6, 10),
                accountCode,
                debitCredit,
                new BigDecimal(amount),
                "TRY",
                "TRANSFER".equals(documentType) ? 23 : 56,
                8000L + rowId,
                documentType,
                "SYNTH-" + rowId,
                "HEADER_ONLY",
                cancelled,
                null);
        return withHash(unhashed);
    }

    private static ProviderActualRow rowForProject(
            long projectId,
            long rowId,
            String accountCode,
            String debitCredit,
            String amount,
            String documentType,
            boolean cancelled) {
        ProviderActualRow base = row(
                rowId, accountCode, debitCredit, amount, documentType, cancelled);
        return withHash(new ProviderActualRow(
                base.sourceSystem(),
                base.sourceLedgerYear(),
                base.sourceCompanyId(),
                projectId,
                base.journalCardId(),
                base.journalRowId(),
                base.postingDate(),
                base.accountCode(),
                base.debitCredit(),
                base.signedAmount(),
                base.currency(),
                base.actionType(),
                base.actionId(),
                base.documentType(),
                base.documentNo(),
                base.resolutionStatus(),
                base.cancelled(),
                null));
    }

    private static ProviderActualRow withHash(ProviderActualRow row) {
        String canonical = String.join("|",
                row.sourceSystem(),
                Integer.toString(row.sourceLedgerYear()),
                Long.toString(row.sourceCompanyId()),
                Long.toString(row.sourceProjectId()),
                Long.toString(row.journalCardId()),
                Long.toString(row.journalRowId()),
                String.valueOf(row.postingDate()),
                String.valueOf(row.accountCode()),
                row.debitCredit(),
                row.signedAmount().toPlainString(),
                row.currency(),
                String.valueOf(row.actionType()),
                String.valueOf(row.actionId()),
                row.documentType(),
                String.valueOf(row.documentNo()),
                row.resolutionStatus(),
                Boolean.toString(row.cancelled()));
        return new ProviderActualRow(
                row.sourceSystem(), row.sourceLedgerYear(), row.sourceCompanyId(),
                row.sourceProjectId(), row.journalCardId(), row.journalRowId(),
                row.postingDate(), row.accountCode(), row.debitCredit(), row.signedAmount(),
                row.currency(), row.actionType(), row.actionId(), row.documentType(),
                row.documentNo(), row.resolutionStatus(), row.cancelled(), sha256(canonical));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static long count(String table, String tenantId) {
        Long value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?",
                Long.class,
                tenantId);
        return value == null ? 0 : value;
    }

    private static <T> T inTransaction(java.util.concurrent.Callable<T> work) {
        return transactions.execute(status -> {
            try {
                return work.call();
            } catch (RuntimeException runtime) {
                throw runtime;
            } catch (Exception checked) {
                throw new IllegalStateException(checked);
            }
        });
    }

    private static final class StubProvider implements ProjectActualProviderClient {
        private final AtomicReference<List<ProviderActualRow>> rows =
                new AtomicReference<>(List.of());
        private final AtomicReference<RuntimeException> failure =
                new AtomicReference<>();

        @Override
        public ProviderActualPage fetch(
                String authorization,
                long companyId,
                long projectId,
                LocalDate from,
                LocalDate to,
                String cursor,
                int limit) {
            RuntimeException configuredFailure = failure.get();
            if (configuredFailure != null) {
                throw configuredFailure;
            }
            return new ProviderActualPage(rows.get(), null, false);
        }
    }
}
