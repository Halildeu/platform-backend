package com.example.report.workcube;

import static com.example.report.workcube.ProjectActualProviderDtos.ProjectActualRow;
import static com.example.report.workcube.ProjectActualProviderDtos.ProjectSourceLineRow;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class ProjectActualProviderRepository {
    private static final Pattern YEARLY_SCHEMA =
            Pattern.compile("workcube_mikrolink_(\\d{4})_(\\d+)");
    private static final String DISCOVER_SCHEMAS = """
            SELECT TABLE_SCHEMA
              FROM INFORMATION_SCHEMA.TABLES
             WHERE TABLE_NAME = N'ACCOUNT_CARD_ROWS'
               AND TABLE_TYPE = N'BASE TABLE'
               AND TABLE_SCHEMA LIKE ?
             ORDER BY TABLE_SCHEMA
            """;

    private final JdbcTemplate jdbc;

    public ProjectActualProviderRepository(
            @Qualifier("workcubeMssqlPlainJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ProjectActualRow> find(
            long companyId,
            long projectId,
            LocalDate from,
            LocalDate to,
            Cursor cursor,
            int limit) {
        int perSchemaLimit = Math.min(Math.max(limit + 1, 2), 2001);
        List<ProjectActualRow> rows = new ArrayList<>();
        for (String schema : findSchemas(companyId, from.getYear(), to.getYear())) {
            int ledgerYear = schemaYear(schema);
            if (cursor != null && ledgerYear < cursor.ledgerYear()) {
                continue;
            }
            long afterRowId =
                    cursor != null && ledgerYear == cursor.ledgerYear()
                            ? cursor.journalRowId()
                            : 0L;
            rows.addAll(querySchema(
                    schema, companyId, projectId, from, to, afterRowId, perSchemaLimit));
        }
        rows.sort(Comparator.comparingInt(ProjectActualRow::sourceLedgerYear)
                .thenComparingLong(ProjectActualRow::journalRowId));
        return rows;
    }

    public List<ProjectSourceLineRow> findSourceLines(
            long companyId,
            long projectId,
            LocalDate from,
            LocalDate to,
            SourceLineCursor cursor,
            int limit) {
        int perSchemaLimit = Math.min(Math.max(limit + 1, 2), 2001);
        List<ProjectSourceLineRow> rows = new ArrayList<>();
        for (String schema : findSchemas(companyId, from.getYear(), to.getYear())) {
            int ledgerYear = schemaYear(schema);
            if (cursor != null && ledgerYear < cursor.ledgerYear()) {
                continue;
            }
            long afterLineId =
                    cursor != null && ledgerYear == cursor.ledgerYear()
                            ? cursor.sourceLineId()
                            : 0L;
            rows.addAll(querySourceLines(
                    schema, companyId, projectId, from, to, afterLineId, perSchemaLimit));
        }
        rows.sort(Comparator.comparingInt(ProjectSourceLineRow::sourceLedgerYear)
                .thenComparingLong(ProjectSourceLineRow::sourceLineId));
        return rows;
    }

    List<String> findSchemas(long companyId, int fromYear, int toYear) {
        return jdbc.queryForList(
                        DISCOVER_SCHEMAS,
                        String.class,
                        "workcube_mikrolink_%_" + companyId)
                .stream()
                .filter(schema -> {
                    Matcher matcher = YEARLY_SCHEMA.matcher(schema);
                    if (!matcher.matches()) {
                        return false;
                    }
                    int year = Integer.parseInt(matcher.group(1));
                    long schemaCompany = Long.parseLong(matcher.group(2));
                    return schemaCompany == companyId && year >= fromYear && year <= toYear;
                })
                .distinct()
                .sorted()
                .toList();
    }

    private List<ProjectActualRow> querySchema(
            String schema,
            long companyId,
            long projectId,
            LocalDate from,
            LocalDate to,
            long afterRowId,
            int limit) {
        if (!YEARLY_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Unexpected yearly accounting schema");
        }
        String sql = """
                SELECT TOP (%d)
                       ACR.CARD_ROW_ID,
                       ACR.CARD_ID,
                       AC.ACTION_DATE,
                       AP.ACCOUNT_CODE,
                       ACR.BA,
                       CASE
                           WHEN ACR.BA = 1 THEN ABS(ACR.AMOUNT)
                           WHEN ACR.BA = 0 THEN -ABS(ACR.AMOUNT)
                           ELSE ACR.AMOUNT
                       END AS SIGNED_AMOUNT,
                       ACR.AMOUNT_CURRENCY AS REPORTING_CURRENCY,
                       AC.ACTION_TYPE,
                       AC.ACTION_ID,
                       AC.ACTION_ROW_ID,
                       AC.IS_CANCEL,
                       CASE
                           WHEN BA.GENEL_VIRMAN_ID IS NOT NULL OR AC.ACTION_TYPE = 23
                               THEN N'TRANSFER'
                           WHEN INV.INVOICE_ID IS NOT NULL THEN N'INVOICE'
                           WHEN EXP.EXPENSE_ID IS NOT NULL THEN N'EXPENSE'
                           WHEN BA.ACTION_ID IS NOT NULL THEN N'BANK'
                           WHEN CA.ACTION_ID IS NOT NULL THEN N'CURRENT_ACCOUNT'
                           WHEN AC.ACTION_TYPE = 113 THEN N'STOCK'
                           WHEN AC.ACTION_TYPE = 13 THEN N'MANUAL_JOURNAL'
                           ELSE N'OTHER'
                       END AS DOCUMENT_TYPE,
                       COALESCE(
                           NULLIF(INV.INVOICE_NUMBER, N''),
                           NULLIF(EXP.PAPER_NO, N''),
                           NULLIF(BA.PAPER_NO, N''),
                           NULLIF(CA.PAPER_NO, N''),
                           NULLIF(AC.PAPER_NO, N'')
                       ) AS DOCUMENT_NO,
                       CASE
                           WHEN AC.ACTION_TYPE = 13 THEN N'MANUAL_JOURNAL'
                           WHEN AC.ACTION_ID IS NULL OR AC.ACTION_ID = 0 THEN N'UNRESOLVED'
                           WHEN IR.INVOICE_ROW_ID IS NOT NULL THEN N'EXACT_LINE'
                           WHEN INV.INVOICE_ID IS NOT NULL
                             OR EXP.EXPENSE_ID IS NOT NULL
                             OR BA.ACTION_ID IS NOT NULL
                             OR CA.ACTION_ID IS NOT NULL THEN N'HEADER_ONLY'
                           WHEN AC.ACTION_TYPE IN (56,57,58,59,120,23,24,25,41,121,250)
                               THEN N'UNRESOLVED'
                           ELSE N'PARTIAL'
                       END AS RESOLUTION_STATUS
                  FROM [%s].[ACCOUNT_CARD_ROWS] ACR
                  LEFT JOIN [%s].[ACCOUNT_CARD] AC
                    ON AC.CARD_ID = ACR.CARD_ID
                  OUTER APPLY (
                      SELECT TOP (1) AP1.ACCOUNT_CODE
                        FROM [%s].[ACCOUNT_PLAN] AP1
                       WHERE AP1.ACCOUNT_CODE = ACR.ACCOUNT_ID
                         AND AP1.SUB_ACCOUNT = 0
                       ORDER BY AP1.ACCOUNT_ID
                  ) AP
                  LEFT JOIN [%s].[INVOICE] INV
                    ON INV.INVOICE_ID = AC.ACTION_ID
                   AND AC.ACTION_TYPE IN (56,57,58,59)
                  LEFT JOIN [%s].[INVOICE_ROW] IR
                    ON IR.INVOICE_ROW_ID = AC.ACTION_ROW_ID
                   AND IR.INVOICE_ID = INV.INVOICE_ID
                  OUTER APPLY (
                      SELECT TOP (1) EXP1.EXPENSE_ID, EXP1.PAPER_NO
                        FROM [%s].[EXPENSE_ITEM_PLANS] EXP1
                       WHERE EXP1.EXPENSE_ID = AC.ACTION_ID
                         AND AC.ACTION_TYPE = 120
                       ORDER BY EXP1.EXPENSE_DATE DESC, EXP1.EXPENSE_ID
                  ) EXP
                  LEFT JOIN [%s].[BANK_ACTIONS] BA
                    ON BA.ACTION_ID = AC.ACTION_ID
                   AND AC.ACTION_TYPE IN (23,24,25,121,250)
                  LEFT JOIN [%s].[CARI_ACTIONS] CA
                    ON CA.ACTION_ID = AC.ACTION_ID
                   AND AC.ACTION_TYPE = 41
                 WHERE ACR.ACC_PROJECT_ID = ?
                   AND AC.ACTION_DATE >= ?
                   AND AC.ACTION_DATE < ?
                   AND ACR.CARD_ROW_ID > ?
                 ORDER BY ACR.CARD_ROW_ID
                """.formatted(
                limit, schema, schema, schema, schema, schema, schema, schema, schema);

        return jdbc.query(
                sql,
                (rs, rowNum) -> new ProjectActualRow(
                        "WORKCUBE",
                        schemaYear(schema),
                        companyId,
                        projectId,
                        rs.getLong("CARD_ID"),
                        rs.getLong("CARD_ROW_ID"),
                        rs.getDate("ACTION_DATE").toLocalDate(),
                        rs.getString("ACCOUNT_CODE"),
                        debitCredit(rs.getInt("BA")),
                        rs.getBigDecimal("SIGNED_AMOUNT"),
                        normalizeCurrency(rs.getString("REPORTING_CURRENCY")),
                        nullableInteger(rs, "ACTION_TYPE"),
                        nullableLong(rs, "ACTION_ID"),
                        nullableLong(rs, "ACTION_ROW_ID"),
                        rs.getString("DOCUMENT_TYPE"),
                        rs.getString("DOCUMENT_NO"),
                        rs.getString("RESOLUTION_STATUS"),
                        rs.getInt("IS_CANCEL") == 1,
                        null),
                projectId,
                Date.valueOf(from),
                Date.valueOf(to.plusDays(1)),
                afterRowId);
    }

    private List<ProjectSourceLineRow> querySourceLines(
            String schema,
            long companyId,
            long projectId,
            LocalDate from,
            LocalDate to,
            long afterLineId,
            int limit) {
        if (!YEARLY_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Unexpected yearly accounting schema");
        }
        String sql = """
                SELECT TOP (%d)
                       IR.INVOICE_ROW_ID,
                       IR.INVOICE_ID,
                       (
                           SELECT COUNT(*)
                             FROM [%s].[INVOICE_ROW] IR2
                            WHERE IR2.INVOICE_ID = IR.INVOICE_ID
                              AND IR2.INVOICE_ROW_ID <= IR.INVOICE_ROW_ID
                       ) AS LINE_ORDINAL,
                       INV.INVOICE_DATE,
                       INV.INVOICE_CAT,
                       INV.PURCHASE_SALES,
                       INV.IS_RETURN,
                       INV.IS_SUCCESS_CANCEL,
                       INV.INVOICE_NUMBER,
                       IR.NAME_PRODUCT,
                       IR.DESCRIPTION,
                       IR.AMOUNT AS QUANTITY,
                       IR.UNIT,
                       IR.PRICE AS UNIT_PRICE,
                       IR.NETTOTAL AS NET_AMOUNT,
                       IR.TAX AS TAX_RATE,
                       IR.TAXTOTAL AS TAX_AMOUNT,
                       IR.GROSSTOTAL AS GROSS_AMOUNT,
                       INV.OTHER_MONEY AS CURRENCY_CODE,
                       IR.ROW_ACC_CODE AS ACCOUNT_CODE
                  FROM [%s].[INVOICE_ROW] IR
                  JOIN [%s].[INVOICE] INV
                    ON INV.INVOICE_ID = IR.INVOICE_ID
                 WHERE IR.ROW_PROJECT_ID = ?
                   AND INV.INVOICE_DATE >= ?
                   AND INV.INVOICE_DATE < ?
                   AND IR.INVOICE_ROW_ID > ?
                 ORDER BY IR.INVOICE_ROW_ID
                """.formatted(limit, schema, schema, schema);

        return jdbc.query(
                sql,
                (rs, rowNum) -> {
                    int invoiceCategory = rs.getInt("INVOICE_CAT");
                    Boolean purchase = nullableBoolean(rs, "PURCHASE_SALES");
                    Boolean returned = nullableBoolean(rs, "IS_RETURN");
                    return new ProjectSourceLineRow(
                            "WORKCUBE",
                            schemaYear(schema),
                            companyId,
                            projectId,
                            rs.getLong("INVOICE_ID"),
                            rs.getLong("INVOICE_ROW_ID"),
                            rs.getInt("LINE_ORDINAL"),
                            rs.getDate("INVOICE_DATE").toLocalDate(),
                            "INVOICE",
                            invoiceKind(invoiceCategory, purchase, returned),
                            rs.getString("INVOICE_NUMBER"),
                            rs.getString("NAME_PRODUCT"),
                            rs.getString("DESCRIPTION"),
                            rs.getBigDecimal("QUANTITY"),
                            rs.getString("UNIT"),
                            rs.getBigDecimal("UNIT_PRICE"),
                            zeroIfNull(rs.getBigDecimal("NET_AMOUNT")),
                            rs.getBigDecimal("TAX_RATE"),
                            zeroIfNull(rs.getBigDecimal("TAX_AMOUNT")),
                            zeroIfNull(rs.getBigDecimal("GROSS_AMOUNT")),
                            normalizeInvoiceCurrency(rs.getString("CURRENCY_CODE")),
                            rs.getString("ACCOUNT_CODE"),
                            Boolean.TRUE.equals(
                                    nullableBoolean(rs, "IS_SUCCESS_CANCEL")),
                            null);
                },
                projectId,
                Date.valueOf(from),
                Date.valueOf(to.plusDays(1)),
                afterLineId);
    }

    private static int schemaYear(String schema) {
        Matcher matcher = YEARLY_SCHEMA.matcher(schema);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unexpected yearly accounting schema");
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static Integer nullableInteger(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static String debitCredit(int ba) {
        if (ba == 1) {
            return "DEBIT";
        }
        if (ba == 0) {
            return "CREDIT";
        }
        return "UNKNOWN";
    }

    private static String normalizeCurrency(String raw) {
        if (raw == null || raw.isBlank() || "0".equals(raw.trim())) {
            return "XXX";
        }
        String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("TL".equals(value) || "YTL".equals(value)) {
            return "TRY";
        }
        return value.length() == 3 ? value : "XXX";
    }

    private static String normalizeInvoiceCurrency(String raw) {
        if (raw == null || raw.isBlank() || "0".equals(raw.trim())) {
            return "TRY";
        }
        return normalizeCurrency(raw);
    }

    static String invoiceKind(
            int category,
            Boolean purchase,
            Boolean returned) {
        if (purchase != null) {
            boolean returnDocument =
                    Boolean.TRUE.equals(returned)
                            || category == 57
                            || category == 60;
            if (purchase) {
                return returnDocument
                        ? "PURCHASE_RETURN"
                        : "PURCHASE_INVOICE";
            }
            return returnDocument
                    ? "SALES_RETURN"
                    : "SALES_INVOICE";
        }
        return switch (category) {
            case 56 -> "PURCHASE_INVOICE";
            case 57 -> "PURCHASE_RETURN";
            case 59 -> "SALES_INVOICE";
            case 60 -> "SALES_RETURN";
            default -> "OTHER_INVOICE";
        };
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record Cursor(int ledgerYear, long journalRowId) {
    }

    public record SourceLineCursor(int ledgerYear, long sourceLineId) {
    }
}
