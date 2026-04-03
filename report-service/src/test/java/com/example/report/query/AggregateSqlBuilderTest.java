package com.example.report.query;

import com.example.report.model.FilterColumnSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AggregateSqlBuilderTest {

    private AggregateSqlBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AggregateSqlBuilder();
    }

    @Nested
    class InjectFiltersIntoSubqueries {

        private final FilterColumnSpec genderSpec = new FilterColumnSpec("GENDER", null, "int");
        private final FilterColumnSpec departmentSpec = new FilterColumnSpec(
                "[workcube_mikrolink].[EMPLOYEES_DEPARTMENT].DEPARTMENT_NAME",
                "INNER JOIN [workcube_mikrolink].[EMPLOYEES_DEPARTMENT] ed ON ed.DEPARTMENT_ID = ROW_DEPARTMENT_HEAD",
                "string");

        @Test
        void returnsUnchangedWhenNoSubqueries() {
            String join = "INNER JOIN [workcube_mikrolink].[EMPLOYEES_PUANTAJ] ep ON ep.PUANTAJ_ID = PUANTAJ_ID";
            Map<String, FilterColumnSpec> cols = Map.of("gender", genderSpec);
            Map<String, String> vals = Map.of("gender", "1");

            String result = builder.injectFiltersIntoSubqueries(join, cols, vals);
            assertEquals(join, result);
        }

        @Test
        void returnsUnchangedWhenNoFilters() {
            String join = "CROSS JOIN (SELECT TOP 1 x FROM t WHERE 1=1) sub";

            String result = builder.injectFiltersIntoSubqueries(join, Map.of(), Map.of());
            assertEquals(join, result);
        }

        @Test
        void returnsNullForNullInput() {
            assertNull(builder.injectFiltersIntoSubqueries(null, Map.of(), Map.of()));
        }

        @Test
        void injectsBareColumnFilterIntoSubqueryWhere() {
            String join = "CROSS JOIN (SELECT TOP 1 PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY SALARY) OVER() AS median_val "
                    + "FROM [workcube_mikrolink].[EMPLOYEES_PUANTAJ_ROWS] epr "
                    + "INNER JOIN [workcube_mikrolink].[EMPLOYEES_PUANTAJ] ep ON ep.PUANTAJ_ID = epr.PUANTAJ_ID "
                    + "WHERE ep.SAL_YEAR = YEAR(GETDATE()) AND SALARY > 0) med_sub";

            Map<String, FilterColumnSpec> cols = Map.of("gender", genderSpec);
            Map<String, String> vals = Map.of("gender", "1");

            String result = builder.injectFiltersIntoSubqueries(join, cols, vals);

            assertTrue(result.contains("GENDER = :_f_gender"), "Should inject gender filter into subquery WHERE");
            assertTrue(result.contains("AND ep.SAL_YEAR"), "Should preserve original WHERE conditions");
        }

        @Test
        void injectsJoinFilterIntoSubquery() {
            String join = "CROSS JOIN (SELECT TOP 1 PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY SALARY) OVER() AS median_val "
                    + "FROM [workcube_mikrolink].[EMPLOYEES_PUANTAJ_ROWS] epr "
                    + "INNER JOIN [workcube_mikrolink].[EMPLOYEES_PUANTAJ] ep ON ep.PUANTAJ_ID = epr.PUANTAJ_ID "
                    + "WHERE ep.SAL_YEAR = YEAR(GETDATE()) AND SALARY > 0) med_sub";

            Map<String, FilterColumnSpec> cols = Map.of("department", departmentSpec);
            Map<String, String> vals = Map.of("department", "IT");

            String result = builder.injectFiltersIntoSubqueries(join, cols, vals);

            assertTrue(result.contains("[workcube_mikrolink].[EMPLOYEES_DEPARTMENT]"),
                    "Should inject department JOIN into subquery");
            assertTrue(result.contains("DEPARTMENT_NAME = :_f_department"),
                    "Should inject department WHERE condition");
        }

        @Test
        void injectsIntoMultipleSubqueryWheres() {
            // YoY KPI pattern: two inner subqueries
            String join = "CROSS JOIN (SELECT AVG(x) FROM "
                    + "(SELECT EMPLOYEE_ID, SALARY FROM [workcube_mikrolink].[EMPLOYEES_PUANTAJ_ROWS] epr "
                    + "INNER JOIN [workcube_mikrolink].[EMPLOYEES_PUANTAJ] ep ON ep.PUANTAJ_ID = epr.PUANTAJ_ID "
                    + "WHERE ep.SAL_YEAR = YEAR(GETDATE()) AND SALARY > 0) cur "
                    + "INNER JOIN "
                    + "(SELECT EMPLOYEE_ID, SALARY FROM [workcube_mikrolink].[EMPLOYEES_PUANTAJ_ROWS] epr "
                    + "INNER JOIN [workcube_mikrolink].[EMPLOYEES_PUANTAJ] ep ON ep.PUANTAJ_ID = epr.PUANTAJ_ID "
                    + "WHERE ep.SAL_YEAR = YEAR(GETDATE()) - 1 AND SALARY > 0) prev "
                    + "ON cur.EMPLOYEE_ID = prev.EMPLOYEE_ID) yoy_sub";

            Map<String, FilterColumnSpec> cols = Map.of("gender", genderSpec);
            Map<String, String> vals = Map.of("gender", "0");

            String result = builder.injectFiltersIntoSubqueries(join, cols, vals);

            // Count occurrences of the injected filter
            int count = countOccurrences(result, "GENDER = :_f_gender");
            assertEquals(2, count, "Should inject filter into both inner subquery WHEREs");
        }

        @Test
        void deduplicatesJoinWhenAlreadyPresent() {
            // Subquery already joins to EMPLOYEES_DEPARTMENT
            String join = "CROSS JOIN (SELECT x FROM [workcube_mikrolink].[EMPLOYEES_PUANTAJ_ROWS] epr "
                    + "INNER JOIN [workcube_mikrolink].[EMPLOYEES_DEPARTMENT] ed ON ed.DEPARTMENT_ID = epr.DEPARTMENT_ID "
                    + "WHERE SALARY > 0) sub";

            Map<String, FilterColumnSpec> cols = Map.of("department", departmentSpec);
            Map<String, String> vals = Map.of("department", "HR");

            String result = builder.injectFiltersIntoSubqueries(join, cols, vals);

            // JOIN should NOT be duplicated
            int joinCount = countOccurrences(result.toUpperCase(), "[EMPLOYEES_DEPARTMENT]");
            assertEquals(2, joinCount, "Should not duplicate the JOIN (original 2 refs preserved)");
            // But WHERE condition should still be injected
            assertTrue(result.contains("DEPARTMENT_NAME = :_f_department"));
        }

        @Test
        void handlesMultipleFiltersCombined() {
            String join = "CROSS JOIN (SELECT TOP 1 val FROM [workcube_mikrolink].[EMPLOYEES_PUANTAJ_ROWS] epr "
                    + "WHERE SALARY > 0) sub";

            Map<String, FilterColumnSpec> cols = new LinkedHashMap<>();
            cols.put("gender", genderSpec);
            cols.put("department", departmentSpec);

            Map<String, String> vals = new LinkedHashMap<>();
            vals.put("gender", "1");
            vals.put("department", "Finance");

            String result = builder.injectFiltersIntoSubqueries(join, cols, vals);

            assertTrue(result.contains("GENDER = :_f_gender"));
            assertTrue(result.contains("DEPARTMENT_NAME = :_f_department"));
            assertTrue(result.contains("[EMPLOYEES_DEPARTMENT]"));
        }
    }

    @Nested
    class BuildAggregateQueryWithFilters {

        @Test
        void addsFilterWhereAndJoinToOuterQuery() {
            var aggregate = new com.example.report.registry.AggregateSpec(
                    "AVG(SALARY)", "ep.SAL_YEAR = YEAR(GETDATE())",
                    null, null, null,
                    "INNER JOIN [workcube_mikrolink].[EMPLOYEES_PUANTAJ] ep ON ep.PUANTAJ_ID = PUANTAJ_ID");

            Map<String, FilterColumnSpec> filterCols = Map.of(
                    "gender", new FilterColumnSpec("GENDER", null, "int"));
            Map<String, String> filterVals = Map.of("gender", "1");

            AggregateSqlBuilder.BuiltQuery result = new AggregateSqlBuilder().buildAggregateQuery(
                    "EMPLOYEES_PUANTAJ_ROWS", "workcube_mikrolink", aggregate,
                    null, null, null, filterCols, filterVals);

            assertTrue(result.sql().contains("GENDER = :_f_gender"), "Outer WHERE should contain filter");
            assertEquals(1, result.params().getValue("_f_gender"), "Param should be parsed as int");
        }
    }

    private static int countOccurrences(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
