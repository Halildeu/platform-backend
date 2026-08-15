package com.example.report.workcube;

import static com.example.report.workcube.BudgetPlanProviderDtos.BudgetPlanRow;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only keyset reader over the Workcube budget-plan family (#3454).
 *
 * <p>Unlike accounting actuals (year-partitioned {@code workcube_mikrolink_<year>_<company>}
 * schemas), the budget tables live in the canonical master schema
 * {@code [workcube_mikrolink]} — the same literal-schema pattern as
 * {@link ProjectOptionsRepository}/{@link CompanyOptionsRepository}. Company and
 * fiscal-year scoping is therefore applied per-row via
 * {@code BUDGET.OUR_COMPANY_ID} / {@code BUDGET.PERIOD_YEAR}, and pagination is a
 * single-schema keyset over {@code BUDGET_PLAN_ROW_ID}. {@code TRY_CAST} keeps
 * the scope predicates robust whether Workcube stores those columns as numeric
 * or varchar; unparseable values fail closed (excluded).
 */
@Repository
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class BudgetPlanProviderRepository {

    private final JdbcTemplate jdbc;

    public BudgetPlanProviderRepository(
            @Qualifier("workcubeMssqlPlainJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BudgetPlanRow> find(
            long companyId,
            int fiscalYear,
            long afterRowId,
            int limit) {
        int cappedLimit = Math.min(Math.max(limit, 2), 2001);
        String sql = """
                SELECT TOP (%d)
                       B.BUDGET_ID,
                       B.BUDGET_NAME,
                       B.BUDGET_STAGE,
                       B.DEPARTMENT_ID,
                       B.PROJECT_ID AS HEADER_PROJECT_ID,
                       B.BRANCH_ID AS HEADER_BRANCH_ID,
                       BP.BUDGET_PLAN_ID,
                       BP.IS_SCENARIO,
                       BP.BRANCH_ID AS PLAN_BRANCH_ID,
                       BP.BUDGET_PLAN_DATE,
                       BPR.BUDGET_PLAN_ROW_ID,
                       BPR.PLAN_DATE,
                       BPR.DETAIL,
                       BPR.BUDGET_ACCOUNT_CODE,
                       BPR.EXP_INC_CENTER_ID,
                       BPR.BUDGET_ITEM_ID,
                       BPR.ACTIVITY_TYPE_ID,
                       BPR.PROJECT_ID AS ROW_PROJECT_ID,
                       BPR.WORKGROUP_ID,
                       BPR.ROW_TOTAL_INCOME,
                       BPR.ROW_TOTAL_EXPENSE
                  FROM [workcube_mikrolink].[BUDGET_PLAN_ROW] BPR
                  JOIN [workcube_mikrolink].[BUDGET_PLAN] BP
                    ON BP.BUDGET_PLAN_ID = BPR.BUDGET_PLAN_ID
                  JOIN [workcube_mikrolink].[BUDGET] B
                    ON B.BUDGET_ID = BP.BUDGET_ID
                 WHERE TRY_CAST(B.OUR_COMPANY_ID AS BIGINT) = ?
                   AND TRY_CAST(B.PERIOD_YEAR AS INT) = ?
                   AND BPR.BUDGET_PLAN_ROW_ID > ?
                 ORDER BY BPR.BUDGET_PLAN_ROW_ID
                """.formatted(cappedLimit);

        return jdbc.query(
                sql,
                (rs, rowNum) -> new BudgetPlanRow(
                        "WORKCUBE",
                        companyId,
                        fiscalYear,
                        rs.getLong("BUDGET_ID"),
                        rs.getString("BUDGET_NAME"),
                        nullableInteger(rs, "BUDGET_STAGE"),
                        rs.getInt("IS_SCENARIO") == 1,
                        rs.getLong("BUDGET_PLAN_ID"),
                        rs.getLong("BUDGET_PLAN_ROW_ID"),
                        planDate(rs),
                        rs.getString("BUDGET_ACCOUNT_CODE"),
                        nullableLong(rs, "EXP_INC_CENTER_ID"),
                        nullableLong(rs, "BUDGET_ITEM_ID"),
                        nullableLong(rs, "ACTIVITY_TYPE_ID"),
                        firstNonNull(
                                nullableLong(rs, "ROW_PROJECT_ID"),
                                nullableLong(rs, "HEADER_PROJECT_ID")),
                        nullableLong(rs, "WORKGROUP_ID"),
                        nullableLong(rs, "DEPARTMENT_ID"),
                        firstNonNull(
                                nullableLong(rs, "PLAN_BRANCH_ID"),
                                nullableLong(rs, "HEADER_BRANCH_ID")),
                        zeroIfNull(rs.getBigDecimal("ROW_TOTAL_INCOME")),
                        zeroIfNull(rs.getBigDecimal("ROW_TOTAL_EXPENSE")),
                        rs.getString("DETAIL"),
                        null),
                companyId,
                fiscalYear,
                afterRowId);
    }

    private static java.time.LocalDate planDate(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        java.sql.Date rowDate = rs.getDate("PLAN_DATE");
        if (rowDate != null) {
            return rowDate.toLocalDate();
        }
        java.sql.Date planDate = rs.getDate("BUDGET_PLAN_DATE");
        return planDate == null ? null : planDate.toLocalDate();
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

    private static Long firstNonNull(Long primary, Long fallback) {
        return primary != null ? primary : fallback;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record Cursor(int fiscalYear, long budgetPlanRowId) {
    }
}
