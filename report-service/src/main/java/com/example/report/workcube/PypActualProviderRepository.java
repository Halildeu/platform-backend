package com.example.report.workcube;

import static com.example.report.workcube.PypActualProviderDtos.PypActualRow;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Ledger-spine actuals with PYP labels (gitops#3496 slice B).
 *
 * <p>Reads the fiscal year's ledger rows from the yearly accounting schema
 * and resolves budget dimensions from the source document lines inside the
 * SAME schema (measured via schema-service snapshot 2026-08-29: the yearly
 * schema carries {@code EXPENSE_ITEMS_ROWS}, {@code EXPENSE_ITEMS} and
 * {@code EXPENSE_CENTER} alongside the ledger — no cross-schema join).
 *
 * <p>Workcube stores "unset" dimension ids as {@code 0}; every dimension
 * predicate goes through {@code NULLIF(x, 0)} so a zero can never join to a
 * master row or masquerade as a real dimension.
 */
@Repository
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class PypActualProviderRepository {
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

    public PypActualProviderRepository(
            @Qualifier("workcubeMssqlPlainJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PypActualRow> find(
            long companyId, int fiscalYear, long afterRowId, int limit) {
        List<String> schemas = jdbc.queryForList(
                        DISCOVER_SCHEMAS,
                        String.class,
                        "workcube_mikrolink_%_" + companyId)
                .stream()
                .filter(schema -> {
                    Matcher matcher = YEARLY_SCHEMA.matcher(schema);
                    return matcher.matches()
                            && Integer.parseInt(matcher.group(1)) == fiscalYear
                            && Long.parseLong(matcher.group(2)) == companyId;
                })
                .distinct()
                .sorted()
                .toList();
        if (schemas.isEmpty()) {
            return List.of();
        }
        // One fiscal year maps to exactly one yearly schema per company.
        return querySchema(schemas.getFirst(), companyId, fiscalYear, afterRowId, limit);
    }

    private List<PypActualRow> querySchema(
            String schema, long companyId, int fiscalYear, long afterRowId, int limit) {
        if (!YEARLY_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("Unexpected yearly accounting schema");
        }
        int boundedLimit = Math.min(Math.max(limit, 1), 2001);
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
                       AC.IS_CANCEL,
                       CASE
                           WHEN BA.GENEL_VIRMAN_ID IS NOT NULL OR AC.ACTION_TYPE = 23
                               THEN N'TRANSFER'
                           WHEN INV.INVOICE_ID IS NOT NULL THEN N'INVOICE'
                           WHEN EXPH.EXPENSE_ID IS NOT NULL THEN N'EXPENSE'
                           WHEN BA.ACTION_ID IS NOT NULL THEN N'BANK'
                           WHEN CA.ACTION_ID IS NOT NULL THEN N'CURRENT_ACCOUNT'
                           WHEN AC.ACTION_TYPE = 113 THEN N'STOCK'
                           WHEN AC.ACTION_TYPE = 13 THEN N'MANUAL_JOURNAL'
                           ELSE N'OTHER'
                       END AS DOCUMENT_TYPE,
                       COALESCE(
                           NULLIF(INV.INVOICE_NUMBER, N''),
                           NULLIF(EXPH.PAPER_NO, N''),
                           NULLIF(BA.PAPER_NO, N''),
                           NULLIF(CA.PAPER_NO, N''),
                           NULLIF(AC.PAPER_NO, N'')
                       ) AS DOCUMENT_NO,
                       CASE
                           WHEN R.EXPENSE_ITEM_ID IS NOT NULL
                                AND IR.INVOICE_ROW_ID IS NOT NULL
                                AND NULLIF(IR.ROW_EXP_ITEM_ID, 0) IS NOT NULL
                               THEN N'INVOICE_LINE'
                           WHEN R.EXPENSE_ITEM_ID IS NOT NULL
                                AND INV.INVOICE_ID IS NOT NULL
                               THEN N'INVOICE_HEADER'
                           WHEN R.EXPENSE_ITEM_ID IS NOT NULL
                                AND EXPH.EXPENSE_ID IS NOT NULL
                               THEN N'EXPENSE_UNIFORM'
                           WHEN EXPH.EXPENSE_ID IS NOT NULL
                                AND EXL.LINE_COUNT > 0
                               THEN N'EXPENSE_MIXED'
                           ELSE N'NONE'
                       END AS DIMENSION_SOURCE,
                       R.EXPENSE_CENTER_ID,
                       EC.EXPENSE_CODE AS EXPENSE_CENTER_CODE,
                       EC.EXPENSE AS EXPENSE_CENTER_NAME,
                       EC.HIERARCHY AS EXPENSE_CENTER_HIERARCHY,
                       R.EXPENSE_ITEM_ID,
                       EI.EXPENSE_ITEM_NAME,
                       EI.EXPENSE_CATEGORY_ID,
                       NULLIF(ACR.ACC_PROJECT_ID, 0) AS PROJECT_ID,
                       INV.INVOICE_ID,
                       IR.INVOICE_ROW_ID,
                       NULLIF(IR.ORDER_ID, 0) AS ORDER_ID,
                       NULLIF(INV.PROGRESS_ID, 0) AS PROGRESS_ID,
                       NULLIF(INV.CONTRACT_ID, 0) AS CONTRACT_ID
                  FROM [%s].[ACCOUNT_CARD_ROWS] ACR
                  JOIN [%s].[ACCOUNT_CARD] AC
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
                  ) EXPH
                  LEFT JOIN [%s].[BANK_ACTIONS] BA
                    ON BA.ACTION_ID = AC.ACTION_ID
                   AND AC.ACTION_TYPE IN (23,24,25,121,250)
                  LEFT JOIN [%s].[CARI_ACTIONS] CA
                    ON CA.ACTION_ID = AC.ACTION_ID
                   AND AC.ACTION_TYPE = 41
                  OUTER APPLY (
                      SELECT COUNT(*) AS LINE_COUNT,
                             COUNT(DISTINCT CONCAT(
                                 COALESCE(NULLIF(EXR.EXPENSE_CENTER_ID, 0), -1),
                                 N'|',
                                 COALESCE(NULLIF(EXR.EXPENSE_ITEM_ID, 0), -1)
                             )) AS DISTINCT_DIMS,
                             MIN(NULLIF(EXR.EXPENSE_CENTER_ID, 0)) AS LINE_CENTER_ID,
                             MIN(NULLIF(EXR.EXPENSE_ITEM_ID, 0)) AS LINE_ITEM_ID
                        FROM [%s].[EXPENSE_ITEMS_ROWS] EXR
                       WHERE EXR.EXPENSE_ID = EXPH.EXPENSE_ID
                  ) EXL
                  CROSS APPLY (
                      SELECT COALESCE(
                                 NULLIF(IR.ROW_EXP_CENTER_ID, 0),
                                 CASE WHEN INV.INVOICE_ID IS NOT NULL
                                      THEN NULLIF(INV.EXPENSE_CENTER_ID, 0) END,
                                 CASE WHEN EXL.DISTINCT_DIMS = 1
                                      THEN EXL.LINE_CENTER_ID END
                             ) AS EXPENSE_CENTER_ID,
                             COALESCE(
                                 NULLIF(IR.ROW_EXP_ITEM_ID, 0),
                                 CASE WHEN INV.INVOICE_ID IS NOT NULL
                                      THEN NULLIF(INV.EXPENSE_ITEM_ID, 0) END,
                                 CASE WHEN EXL.DISTINCT_DIMS = 1
                                      THEN EXL.LINE_ITEM_ID END
                             ) AS EXPENSE_ITEM_ID
                  ) R
                  LEFT JOIN [%s].[EXPENSE_CENTER] EC
                    ON EC.EXPENSE_ID = R.EXPENSE_CENTER_ID
                  LEFT JOIN [%s].[EXPENSE_ITEMS] EI
                    ON EI.EXPENSE_ITEM_ID = R.EXPENSE_ITEM_ID
                 WHERE AC.ACTION_DATE >= ?
                   AND AC.ACTION_DATE < ?
                   AND ACR.CARD_ROW_ID > ?
                 ORDER BY ACR.CARD_ROW_ID
                """.formatted(
                boundedLimit,
                schema, schema, schema, schema, schema, schema, schema, schema,
                schema, schema, schema);

        LocalDate yearStart = LocalDate.of(fiscalYear, 1, 1);
        return jdbc.query(
                sql,
                (rs, rowNum) -> new PypActualRow(
                        "WORKCUBE",
                        fiscalYear,
                        companyId,
                        rs.getLong("CARD_ID"),
                        rs.getLong("CARD_ROW_ID"),
                        rs.getDate("ACTION_DATE").toLocalDate(),
                        rs.getString("ACCOUNT_CODE"),
                        debitCredit(rs.getInt("BA")),
                        rs.getBigDecimal("SIGNED_AMOUNT"),
                        normalizeCurrency(rs.getString("REPORTING_CURRENCY")),
                        nullableInteger(rs, "ACTION_TYPE"),
                        nullableLong(rs, "ACTION_ID"),
                        rs.getString("DOCUMENT_TYPE"),
                        rs.getString("DOCUMENT_NO"),
                        rs.getInt("IS_CANCEL") == 1,
                        rs.getString("DIMENSION_SOURCE"),
                        nullableLong(rs, "EXPENSE_CENTER_ID"),
                        rs.getString("EXPENSE_CENTER_CODE"),
                        rs.getString("EXPENSE_CENTER_NAME"),
                        rs.getString("EXPENSE_CENTER_HIERARCHY"),
                        nullableLong(rs, "EXPENSE_ITEM_ID"),
                        rs.getString("EXPENSE_ITEM_NAME"),
                        nullableLong(rs, "EXPENSE_CATEGORY_ID"),
                        nullableLong(rs, "PROJECT_ID"),
                        nullableLong(rs, "INVOICE_ID"),
                        nullableLong(rs, "INVOICE_ROW_ID"),
                        nullableLong(rs, "ORDER_ID"),
                        nullableLong(rs, "PROGRESS_ID"),
                        nullableLong(rs, "CONTRACT_ID"),
                        null),
                Date.valueOf(yearStart),
                Date.valueOf(yearStart.plusYears(1)),
                afterRowId);
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
}
