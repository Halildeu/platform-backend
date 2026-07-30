package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class ProjectActualProviderRepositoryMssqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static ProjectActualProviderRepository repository;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(MSSQL.getDriverClassName());
        dataSource.setUrl(MSSQL.getJdbcUrl() + ";encrypt=false;trustServerCertificate=true");
        dataSource.setUsername(MSSQL.getUsername());
        dataSource.setPassword(MSSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        for (String schema : List.of(
                "workcube_mikrolink_2026_35",
                "workcube_mikrolink_2026_350")) {
            jdbc.execute("EXEC('CREATE SCHEMA [" + schema + "]')");
            createAccountingTables(jdbc, schema);
        }
        insertRows(jdbc, "workcube_mikrolink_2026_35", 44200);
        insertRows(jdbc, "workcube_mikrolink_2026_350", 99999);
        repository = new ProjectActualProviderRepository(jdbc);
    }

    @Test
    void returnsNormalizedRowsForExactCompanyProjectAndDateScope() {
        var rows = repository.find(
                35L,
                44200L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                null,
                10);

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.sourceCompanyId()).isEqualTo(35L);
            assertThat(row.sourceProjectId()).isEqualTo(44200L);
            assertThat(row.sourceLedgerYear()).isEqualTo(2026);
        });
        assertThat(rows.getFirst())
                .satisfies(row -> {
                    assertThat(row.accountCode()).isEqualTo("740.01");
                    assertThat(row.debitCredit()).isEqualTo("DEBIT");
                    assertThat(row.signedAmount()).isEqualByComparingTo("100.00");
                    assertThat(row.currency()).isEqualTo("TRY");
                    assertThat(row.documentType()).isEqualTo("INVOICE");
                    assertThat(row.documentNo()).isEqualTo("INV-10");
                    assertThat(row.resolutionStatus()).isEqualTo("EXACT_LINE");
                });
        assertThat(rows.getLast())
                .satisfies(row -> {
                    assertThat(row.accountCode()).isEqualTo("102.01");
                    assertThat(row.debitCredit()).isEqualTo("CREDIT");
                    assertThat(row.signedAmount()).isEqualByComparingTo("-25.00");
                    assertThat(row.currency()).isEqualTo("EUR");
                    assertThat(row.documentType()).isEqualTo("TRANSFER");
                    assertThat(row.documentNo()).isEqualTo("BNK-20");
                    assertThat(row.resolutionStatus()).isEqualTo("HEADER_ONLY");
                });
    }

    @Test
    void returnsInvoiceLinesIndependentlyFromAccountingRowLinkage() {
        var rows = repository.findSourceLines(
                35L,
                44200L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                null,
                10);

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.sourceCompanyId()).isEqualTo(35L);
            assertThat(row.sourceProjectId()).isEqualTo(44200L);
            assertThat(row.sourceDocumentId()).isEqualTo(10L);
            assertThat(row.documentKind()).isEqualTo("PURCHASE_INVOICE");
            assertThat(row.currency()).isEqualTo("TRY");
        });
        assertThat(rows.getFirst())
                .satisfies(row -> {
                    assertThat(row.sourceLineId()).isEqualTo(100L);
                    assertThat(row.lineOrdinal()).isEqualTo(1);
                    assertThat(row.productName()).isEqualTo("Electricity");
                    assertThat(row.netAmount()).isEqualByComparingTo("100.00");
                    assertThat(row.taxAmount()).isEqualByComparingTo("20.00");
                    assertThat(row.grossAmount()).isEqualByComparingTo("120.00");
                });
        assertThat(rows.getLast())
                .satisfies(row -> {
                    assertThat(row.sourceLineId()).isEqualTo(101L);
                    assertThat(row.lineOrdinal()).isEqualTo(2);
                    assertThat(row.productName()).isEqualTo("Cleaning");
                });
    }

    private static void createAccountingTables(JdbcTemplate jdbc, String schema) {
        jdbc.execute("""
                CREATE TABLE [%s].[ACCOUNT_CARD_ROWS] (
                    CARD_ROW_ID BIGINT NOT NULL PRIMARY KEY,
                    CARD_ID BIGINT NOT NULL,
                    AMOUNT DECIMAL(19,4) NOT NULL,
                    AMOUNT_CURRENCY NVARCHAR(8) NULL,
                    BA INT NOT NULL,
                    ACC_PROJECT_ID BIGINT NULL,
                    ACCOUNT_ID NVARCHAR(80) NULL
                )
                """.formatted(schema));
        jdbc.execute("""
                CREATE TABLE [%s].[ACCOUNT_CARD] (
                    CARD_ID BIGINT NOT NULL PRIMARY KEY,
                    ACTION_DATE DATETIME NOT NULL,
                    ACTION_TYPE INT NULL,
                    ACTION_ID BIGINT NULL,
                    ACTION_ROW_ID BIGINT NULL,
                    IS_CANCEL INT NOT NULL,
                    PAPER_NO NVARCHAR(160) NULL
                )
                """.formatted(schema));
        jdbc.execute("""
                CREATE TABLE [%s].[ACCOUNT_PLAN] (
                    ACCOUNT_ID BIGINT NOT NULL PRIMARY KEY,
                    ACCOUNT_CODE NVARCHAR(80) NULL,
                    SUB_ACCOUNT BIT NOT NULL
                )
                """.formatted(schema));
        jdbc.execute("""
                CREATE TABLE [%s].[INVOICE] (
                    INVOICE_ID BIGINT NOT NULL PRIMARY KEY,
                    INVOICE_NUMBER NVARCHAR(160) NULL,
                    INVOICE_DATE DATETIME NOT NULL,
                    INVOICE_CAT INT NOT NULL
                )
                """.formatted(schema));
        jdbc.execute("""
                CREATE TABLE [%s].[INVOICE_ROW] (
                    INVOICE_ROW_ID BIGINT NOT NULL PRIMARY KEY,
                    INVOICE_ID BIGINT NOT NULL,
                    NAME_PRODUCT NVARCHAR(500) NULL,
                    DESCRIPTION NVARCHAR(MAX) NULL,
                    AMOUNT DECIMAL(19,6) NULL,
                    UNIT NVARCHAR(40) NULL,
                    PRICE DECIMAL(19,6) NULL,
                    NETTOTAL DECIMAL(19,4) NULL,
                    TAX DECIMAL(9,4) NULL,
                    TAXTOTAL DECIMAL(19,4) NULL,
                    GROSSTOTAL DECIMAL(19,4) NULL,
                    OTHER_MONEY NVARCHAR(8) NULL,
                    ROW_ACC_CODE NVARCHAR(80) NULL,
                    ROW_PROJECT_ID BIGINT NULL
                )
                """.formatted(schema));
        jdbc.execute("""
                CREATE TABLE [%s].[EXPENSE_ITEM_PLANS] (
                    EXPENSE_ID BIGINT NOT NULL PRIMARY KEY,
                    PAPER_NO NVARCHAR(160) NULL,
                    EXPENSE_DATE DATETIME NULL
                )
                """.formatted(schema));
        jdbc.execute("""
                CREATE TABLE [%s].[BANK_ACTIONS] (
                    ACTION_ID BIGINT NOT NULL PRIMARY KEY,
                    PAPER_NO NVARCHAR(160) NULL,
                    GENEL_VIRMAN_ID BIGINT NULL
                )
                """.formatted(schema));
        jdbc.execute("""
                CREATE TABLE [%s].[CARI_ACTIONS] (
                    ACTION_ID BIGINT NOT NULL PRIMARY KEY,
                    PAPER_NO NVARCHAR(160) NULL
                )
                """.formatted(schema));
    }

    private static void insertRows(
            JdbcTemplate jdbc,
            String schema,
            long projectId) {
        jdbc.update("""
                INSERT INTO [%s].[ACCOUNT_PLAN]
                    (ACCOUNT_ID, ACCOUNT_CODE, SUB_ACCOUNT)
                VALUES (1, N'740.01', 0), (2, N'102.01', 0)
                """.formatted(schema));
        jdbc.update("""
                INSERT INTO [%s].[ACCOUNT_CARD]
                    (CARD_ID, ACTION_DATE, ACTION_TYPE, ACTION_ID, ACTION_ROW_ID,
                     IS_CANCEL, PAPER_NO)
                VALUES
                    (1000, '2026-06-10', 56, 10, 100, 0, N'ACC-10'),
                    (2000, '2026-06-11', 23, 20, NULL, 0, N'ACC-20')
                """.formatted(schema));
        jdbc.update("""
                INSERT INTO [%s].[ACCOUNT_CARD_ROWS]
                    (CARD_ROW_ID, CARD_ID, AMOUNT, AMOUNT_CURRENCY, BA, ACC_PROJECT_ID, ACCOUNT_ID)
                VALUES
                    (1, 1000, 100.00, N'TL', 1, ?, N'740.01'),
                    (2, 2000, 25.00, N'EUR', 0, ?, N'102.01')
                """.formatted(schema), projectId, projectId);
        jdbc.update("""
                INSERT INTO [%s].[INVOICE]
                    (INVOICE_ID, INVOICE_NUMBER, INVOICE_DATE, INVOICE_CAT)
                VALUES (10, N'INV-10', '2026-06-10', 56)
                """.formatted(schema));
        jdbc.update("""
                INSERT INTO [%s].[INVOICE_ROW]
                    (INVOICE_ROW_ID, INVOICE_ID, NAME_PRODUCT, DESCRIPTION,
                     AMOUNT, UNIT, PRICE, NETTOTAL, TAX, TAXTOTAL, GROSSTOTAL,
                     OTHER_MONEY, ROW_ACC_CODE, ROW_PROJECT_ID)
                VALUES
                    (100, 10, N'Electricity', N'June electricity', 1, N'EA',
                     100, 100, 20, 20, 120, N'TL', N'740.01', ?),
                    (101, 10, N'Cleaning', N'June cleaning', 1, N'EA',
                     25, 25, 20, 5, 30, NULL, NULL, ?)
                """.formatted(schema), projectId, projectId);
        jdbc.update("""
                INSERT INTO [%s].[BANK_ACTIONS]
                    (ACTION_ID, PAPER_NO, GENEL_VIRMAN_ID)
                VALUES (20, N'BNK-20', 900)
                """.formatted(schema));
    }
}
