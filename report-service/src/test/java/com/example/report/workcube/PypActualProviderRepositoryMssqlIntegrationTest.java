package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.workcube.PypActualProviderDtos.PypActualRow;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-MSSQL proof of the ledger-spine PYP resolution (gitops#3496 slice B).
 * Seeds the yearly accounting schema exactly as measured from the live
 * schema-service snapshot (2026-08-29): ledger + invoice + expense + bank
 * tables AND the year-schema copies of EXPENSE_ITEMS / EXPENSE_CENTER.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class PypActualProviderRepositoryMssqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static PypActualProviderRepository repository;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(MSSQL.getDriverClassName());
        dataSource.setUrl(MSSQL.getJdbcUrl() + ";encrypt=false;trustServerCertificate=true");
        dataSource.setUsername(MSSQL.getUsername());
        dataSource.setPassword(MSSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        createYearSchema(jdbc, "workcube_mikrolink_2026_1");
        // Decoy: same year, different company — must never leak into results.
        createYearSchema(jdbc, "workcube_mikrolink_2026_9");
        seedDecoy(jdbc, "workcube_mikrolink_2026_9");

        String s = "workcube_mikrolink_2026_1";
        seedMasters(jdbc, s);

        // Card 9001 / row 70001 — purchase invoice with an exact line match:
        // line-level dims + order/progress/contract lineage.
        insertCard(jdbc, s, 9001, "2026-03-15", 56, 4001L, 88001L, null, 0);
        insertInvoice(jdbc, s, 4001, "FTR-2026-17", 12L, 77L, 3501L, 9101L);
        insertInvoiceRow(jdbc, s, 88001, 4001, 12, 77, 3501L, 44200L);
        insertLedgerRow(jdbc, s, 70001, 9001, "740.01", 1, 1500, 44200L);

        // Card 9002 / row 70002 — invoice whose LINE dims are zero (=unset):
        // must fall back to the invoice HEADER dims.
        insertCard(jdbc, s, 9002, "2026-04-02", 56, 4002L, 88002L, null, 0);
        insertInvoice(jdbc, s, 4002, "FTR-2026-18", 13L, 78L, null, null);
        insertInvoiceRow(jdbc, s, 88002, 4002, 0, 0, null, null);
        insertLedgerRow(jdbc, s, 70002, 9002, "740.02", 1, 250, null);

        // Card 9003 / row 70003 — masraf whose lines all agree on one
        // (center, item) pair -> EXPENSE_UNIFORM.
        insertCard(jdbc, s, 9003, "2026-05-05", 120, 6001L, null, "MSR-9", 0);
        insertExpenseHeader(jdbc, s, 6001, "2026-05-05");
        insertExpenseLine(jdbc, s, 61001, 6001, 14, 79);
        insertExpenseLine(jdbc, s, 61002, 6001, 14, 79);
        insertLedgerRow(jdbc, s, 70003, 9003, "770.01", 1, 90, null);

        // Card 9004 / row 70004 — masraf with DIFFERING line dims ->
        // EXPENSE_MIXED, nothing guessed.
        insertCard(jdbc, s, 9004, "2026-05-06", 120, 6002L, null, "MSR-10", 0);
        insertExpenseHeader(jdbc, s, 6002, "2026-05-06");
        insertExpenseLine(jdbc, s, 62001, 6002, 14, 79);
        insertExpenseLine(jdbc, s, 62002, 6002, 15, 80);
        insertLedgerRow(jdbc, s, 70004, 9004, "770.02", 1, 40, null);

        // Card 9006 / row 70006 — the measured live shape: ACTION_ROW_ID is
        // unset (0), so no exact line join exists, but every invoice line
        // agrees on one (center, item) pair AND one order -> INVOICE_UNIFORM
        // with order lineage.
        insertCard(jdbc, s, 9006, "2026-04-10", 56, 4003L, 0L, null, 0);
        insertInvoice(jdbc, s, 4003, "FTR-2026-19", null, null, null, 9102L);
        insertInvoiceRow(jdbc, s, 88003, 4003, 12, 77, 3502L, null);
        insertInvoiceRow(jdbc, s, 88004, 4003, 12, 77, 3502L, null);
        insertLedgerRow(jdbc, s, 70006, 9006, "740.03", 1, 700, null);

        // Card 9007 / row 70007 — unset ACTION_ROW_ID and DISAGREEING invoice
        // lines -> INVOICE_MIXED, nothing guessed.
        insertCard(jdbc, s, 9007, "2026-04-11", 56, 4004L, 0L, null, 0);
        insertInvoice(jdbc, s, 4004, "FTR-2026-20", null, null, null, null);
        insertInvoiceRow(jdbc, s, 88005, 4004, 12, 77, null, null);
        insertInvoiceRow(jdbc, s, 88006, 4004, 13, 78, null, null);
        insertLedgerRow(jdbc, s, 70007, 9007, "740.04", 1, 55, null);

        // Card 9005 / row 70005 — bank transfer (virman): TRANSFER + NONE.
        insertCard(jdbc, s, 9005, "2026-06-01", 24, 7001L, null, null, 0);
        jdbc.update("INSERT INTO [" + s + "].[BANK_ACTIONS]"
                        + " (ACTION_ID, PAPER_NO, GENEL_VIRMAN_ID) VALUES (?, ?, ?)",
                7001L, "BNK-1", 555L);
        insertLedgerRow(jdbc, s, 70005, 9005, "102.01", 0, 40, null);

        repository = new PypActualProviderRepository(jdbc);
    }

    @Test
    void resolvesInvoiceLineDimensionsWithNamesAndLineage() {
        List<PypActualRow> rows = repository.find(1L, 2026, 0L, 50);
        PypActualRow row = byJournalRow(rows, 70001L);

        assertThat(row.dimensionSource()).isEqualTo("INVOICE_LINE");
        assertThat(row.documentType()).isEqualTo("INVOICE");
        assertThat(row.documentNo()).isEqualTo("FTR-2026-17");
        assertThat(row.expenseCenterId()).isEqualTo(12L);
        assertThat(row.expenseCenterCode()).isEqualTo("PYP.01.02");
        assertThat(row.expenseCenterName()).isEqualTo("Kaba İşler");
        assertThat(row.expenseCenterHierarchy()).isEqualTo("001.002");
        assertThat(row.expenseItemId()).isEqualTo(77L);
        assertThat(row.expenseItemName()).isEqualTo("Kalıp İşçiliği");
        assertThat(row.expenseCategoryId()).isEqualTo(5L);
        assertThat(row.projectId()).isEqualTo(44200L);
        assertThat(row.invoiceId()).isEqualTo(4001L);
        assertThat(row.invoiceRowId()).isEqualTo(88001L);
        assertThat(row.orderId()).isEqualTo(3501L);
        assertThat(row.progressId()).isEqualTo(9101L);
        assertThat(row.signedAmount()).isEqualByComparingTo("1500");
        assertThat(row.debitCredit()).isEqualTo("DEBIT");
    }

    @Test
    void zeroLineDimensionsFallBackToTheInvoiceHeader() {
        PypActualRow row = byJournalRow(repository.find(1L, 2026, 0L, 50), 70002L);

        assertThat(row.dimensionSource()).isEqualTo("INVOICE_HEADER");
        assertThat(row.expenseCenterId()).isEqualTo(13L);
        assertThat(row.expenseItemId()).isEqualTo(78L);
        // Zero can never masquerade as a real dimension or lineage id.
        assertThat(row.orderId()).isNull();
        assertThat(row.progressId()).isNull();
        assertThat(row.projectId()).isNull();
    }

    @Test
    void uniformExpenseLinesResolveWhileMixedOnesRefuseToGuess() {
        List<PypActualRow> rows = repository.find(1L, 2026, 0L, 50);

        PypActualRow uniform = byJournalRow(rows, 70003L);
        assertThat(uniform.documentType()).isEqualTo("EXPENSE");
        assertThat(uniform.dimensionSource()).isEqualTo("EXPENSE_UNIFORM");
        assertThat(uniform.expenseCenterId()).isEqualTo(14L);
        assertThat(uniform.expenseItemId()).isEqualTo(79L);

        PypActualRow mixed = byJournalRow(rows, 70004L);
        assertThat(mixed.dimensionSource()).isEqualTo("EXPENSE_MIXED");
        assertThat(mixed.expenseCenterId()).isNull();
        assertThat(mixed.expenseItemId()).isNull();
        assertThat(mixed.expenseCenterName()).isNull();
    }

    @Test
    void bankVirmanRowsAreTransfersWithoutDimensions() {
        PypActualRow row = byJournalRow(repository.find(1L, 2026, 0L, 50), 70005L);

        assertThat(row.documentType()).isEqualTo("TRANSFER");
        assertThat(row.dimensionSource()).isEqualTo("NONE");
        assertThat(row.expenseCenterId()).isNull();
        assertThat(row.signedAmount()).isEqualByComparingTo("-40");
        assertThat(row.debitCredit()).isEqualTo("CREDIT");
    }

    @Test
    void unsetActionRowIdResolvesThroughUniformInvoiceLines() {
        List<PypActualRow> rows = repository.find(1L, 2026, 0L, 50);

        PypActualRow uniform = byJournalRow(rows, 70006L);
        assertThat(uniform.dimensionSource()).isEqualTo("INVOICE_UNIFORM");
        assertThat(uniform.expenseCenterId()).isEqualTo(12L);
        assertThat(uniform.expenseItemId()).isEqualTo(77L);
        assertThat(uniform.expenseItemName()).isEqualTo("Kalıp İşçiliği");
        assertThat(uniform.orderId()).isEqualTo(3502L);
        assertThat(uniform.progressId()).isEqualTo(9102L);
        assertThat(uniform.invoiceRowId()).isNull();

        PypActualRow mixed = byJournalRow(rows, 70007L);
        assertThat(mixed.dimensionSource()).isEqualTo("INVOICE_MIXED");
        assertThat(mixed.expenseCenterId()).isNull();
        assertThat(mixed.expenseItemId()).isNull();
        assertThat(mixed.orderId()).isNull();
    }

    @Test
    void scopesToTheCompanySchemaAndPaginatesByKeyset() {
        List<PypActualRow> all = repository.find(1L, 2026, 0L, 50);
        assertThat(all).extracting(PypActualRow::journalRowId)
                .containsExactly(70001L, 70002L, 70003L, 70004L, 70005L,
                        70006L, 70007L);
        assertThat(all).allSatisfy(row ->
                assertThat(row.sourceCompanyId()).isEqualTo(1L));

        List<PypActualRow> secondPage = repository.find(1L, 2026, 70002L, 2);
        assertThat(secondPage).extracting(PypActualRow::journalRowId)
                .containsExactly(70003L, 70004L);

        assertThat(repository.find(1L, 2025, 0L, 50)).isEmpty();
    }

    private static PypActualRow byJournalRow(List<PypActualRow> rows, long journalRowId) {
        return rows.stream()
                .filter(row -> row.journalRowId() == journalRowId)
                .findFirst()
                .orElseThrow();
    }

    private static void createYearSchema(JdbcTemplate jdbc, String schema) {
        jdbc.execute("EXEC('CREATE SCHEMA [" + schema + "]')");
        jdbc.execute("CREATE TABLE [" + schema + "].[ACCOUNT_CARD_ROWS] ("
                + "CARD_ROW_ID BIGINT PRIMARY KEY, CARD_ID BIGINT,"
                + "ACCOUNT_ID NVARCHAR(40), BA INT, AMOUNT DECIMAL(19,4),"
                + "AMOUNT_CURRENCY NVARCHAR(8), ACC_PROJECT_ID BIGINT)");
        jdbc.execute("CREATE TABLE [" + schema + "].[ACCOUNT_CARD] ("
                + "CARD_ID BIGINT PRIMARY KEY, ACTION_DATE DATETIME,"
                + "ACTION_TYPE INT, ACTION_ID BIGINT, ACTION_ROW_ID BIGINT,"
                + "PAPER_NO NVARCHAR(40), IS_CANCEL INT)");
        jdbc.execute("CREATE TABLE [" + schema + "].[ACCOUNT_PLAN] ("
                + "ACCOUNT_ID BIGINT PRIMARY KEY, ACCOUNT_CODE NVARCHAR(40),"
                + "SUB_ACCOUNT INT)");
        jdbc.execute("CREATE TABLE [" + schema + "].[INVOICE] ("
                + "INVOICE_ID BIGINT PRIMARY KEY, INVOICE_NUMBER NVARCHAR(40),"
                + "EXPENSE_CENTER_ID BIGINT, EXPENSE_ITEM_ID BIGINT,"
                + "CONTRACT_ID BIGINT, PROGRESS_ID BIGINT)");
        jdbc.execute("CREATE TABLE [" + schema + "].[INVOICE_ROW] ("
                + "INVOICE_ROW_ID BIGINT PRIMARY KEY, INVOICE_ID BIGINT,"
                + "ROW_EXP_CENTER_ID BIGINT, ROW_EXP_ITEM_ID BIGINT,"
                + "ORDER_ID BIGINT, ROW_PROJECT_ID BIGINT)");
        jdbc.execute("CREATE TABLE [" + schema + "].[EXPENSE_ITEM_PLANS] ("
                + "EXPENSE_ID BIGINT, EXPENSE_DATE DATETIME, PAPER_NO NVARCHAR(40))");
        jdbc.execute("CREATE TABLE [" + schema + "].[EXPENSE_ITEMS_ROWS] ("
                + "EXP_ITEM_ROWS_ID BIGINT PRIMARY KEY, EXPENSE_ID BIGINT,"
                + "EXPENSE_CENTER_ID BIGINT, EXPENSE_ITEM_ID BIGINT)");
        jdbc.execute("CREATE TABLE [" + schema + "].[EXPENSE_ITEMS] ("
                + "EXPENSE_ITEM_ID BIGINT PRIMARY KEY,"
                + "EXPENSE_ITEM_NAME NVARCHAR(200), EXPENSE_CATEGORY_ID BIGINT)");
        jdbc.execute("CREATE TABLE [" + schema + "].[EXPENSE_CENTER] ("
                + "EXPENSE_ID BIGINT PRIMARY KEY, EXPENSE NVARCHAR(200),"
                + "EXPENSE_CODE NVARCHAR(40), HIERARCHY NVARCHAR(120))");
        jdbc.execute("CREATE TABLE [" + schema + "].[BANK_ACTIONS] ("
                + "ACTION_ID BIGINT, PAPER_NO NVARCHAR(40), GENEL_VIRMAN_ID BIGINT)");
        jdbc.execute("CREATE TABLE [" + schema + "].[CARI_ACTIONS] ("
                + "ACTION_ID BIGINT, PAPER_NO NVARCHAR(40))");
    }

    private static void seedMasters(JdbcTemplate jdbc, String schema) {
        jdbc.update("INSERT INTO [" + schema + "].[EXPENSE_CENTER]"
                + " (EXPENSE_ID, EXPENSE, EXPENSE_CODE, HIERARCHY)"
                + " VALUES (12, N'Kaba İşler', N'PYP.01.02', N'001.002'),"
                + " (13, N'İnce İşler', N'PYP.01.03', N'001.003'),"
                + " (14, N'Şantiye Genel', N'PYP.02.01', N'002.001')");
        jdbc.update("INSERT INTO [" + schema + "].[EXPENSE_ITEMS]"
                + " (EXPENSE_ITEM_ID, EXPENSE_ITEM_NAME, EXPENSE_CATEGORY_ID)"
                + " VALUES (77, N'Kalıp İşçiliği', 5), (78, N'Demir İşçiliği', 5),"
                + " (79, N'Nakliye', 6)");
    }

    private static void seedDecoy(JdbcTemplate jdbc, String schema) {
        jdbc.update("INSERT INTO [" + schema + "].[ACCOUNT_CARD]"
                + " (CARD_ID, ACTION_DATE, ACTION_TYPE, ACTION_ID, ACTION_ROW_ID,"
                + " PAPER_NO, IS_CANCEL) VALUES (1, '2026-03-01', 13, 0, 0, N'X', 0)");
        jdbc.update("INSERT INTO [" + schema + "].[ACCOUNT_CARD_ROWS]"
                + " (CARD_ROW_ID, CARD_ID, ACCOUNT_ID, BA, AMOUNT, AMOUNT_CURRENCY,"
                + " ACC_PROJECT_ID) VALUES (1, 1, N'100', 1, 999, N'TL', 0)");
    }

    private static void insertCard(
            JdbcTemplate jdbc, String schema, long cardId, String date,
            int actionType, Long actionId, Long actionRowId, String paperNo,
            int cancelled) {
        jdbc.update("INSERT INTO [" + schema + "].[ACCOUNT_CARD]"
                        + " (CARD_ID, ACTION_DATE, ACTION_TYPE, ACTION_ID,"
                        + " ACTION_ROW_ID, PAPER_NO, IS_CANCEL)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                cardId, date, actionType, actionId, actionRowId, paperNo, cancelled);
    }

    private static void insertLedgerRow(
            JdbcTemplate jdbc, String schema, long rowId, long cardId,
            String accountCode, int ba, int amount, Long projectId) {
        jdbc.update("INSERT INTO [" + schema + "].[ACCOUNT_PLAN]"
                        + " (ACCOUNT_ID, ACCOUNT_CODE, SUB_ACCOUNT) VALUES (?, ?, 0)",
                rowId, accountCode);
        jdbc.update("INSERT INTO [" + schema + "].[ACCOUNT_CARD_ROWS]"
                        + " (CARD_ROW_ID, CARD_ID, ACCOUNT_ID, BA, AMOUNT,"
                        + " AMOUNT_CURRENCY, ACC_PROJECT_ID)"
                        + " VALUES (?, ?, ?, ?, ?, N'TL', ?)",
                rowId, cardId, accountCode, ba, amount,
                projectId == null ? 0L : projectId);
    }

    private static void insertInvoice(
            JdbcTemplate jdbc, String schema, long invoiceId, String number,
            Long headerCenterId, Long headerItemId, Long contractId, Long progressId) {
        jdbc.update("INSERT INTO [" + schema + "].[INVOICE]"
                        + " (INVOICE_ID, INVOICE_NUMBER, EXPENSE_CENTER_ID,"
                        + " EXPENSE_ITEM_ID, CONTRACT_ID, PROGRESS_ID)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                invoiceId, number, headerCenterId, headerItemId,
                contractId == null ? 0L : contractId,
                progressId == null ? 0L : progressId);
    }

    private static void insertInvoiceRow(
            JdbcTemplate jdbc, String schema, long rowId, long invoiceId,
            long centerCandidate, long itemCandidate, Long orderId, Long projectId) {
        jdbc.update("INSERT INTO [" + schema + "].[INVOICE_ROW]"
                        + " (INVOICE_ROW_ID, INVOICE_ID, ROW_EXP_CENTER_ID,"
                        + " ROW_EXP_ITEM_ID, ORDER_ID, ROW_PROJECT_ID)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                rowId, invoiceId, centerCandidate, itemCandidate,
                orderId == null ? 0L : orderId,
                projectId == null ? 0L : projectId);
    }

    private static void insertExpenseHeader(
            JdbcTemplate jdbc, String schema, long expenseId, String date) {
        jdbc.update("INSERT INTO [" + schema + "].[EXPENSE_ITEM_PLANS]"
                        + " (EXPENSE_ID, EXPENSE_DATE, PAPER_NO) VALUES (?, ?, NULL)",
                expenseId, date);
    }

    private static void insertExpenseLine(
            JdbcTemplate jdbc, String schema, long lineId, long expenseId,
            long centerId, long itemId) {
        jdbc.update("INSERT INTO [" + schema + "].[EXPENSE_ITEMS_ROWS]"
                        + " (EXP_ITEM_ROWS_ID, EXPENSE_ID, EXPENSE_CENTER_ID,"
                        + " EXPENSE_ITEM_ID) VALUES (?, ?, ?, ?)",
                lineId, expenseId, centerId, itemId);
    }
}
