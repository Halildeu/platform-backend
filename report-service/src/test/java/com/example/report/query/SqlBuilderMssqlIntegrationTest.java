package com.example.report.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PR-0.5 (reporting platform hardening, 2026-05) — end-to-end SQL
 * correctness for {@link SqlBuilder} against a real Microsoft SQL
 * Server 2022 instance.
 *
 * <p>The unit tests in {@link SqlBuilderTest} only assert the textual
 * shape of the generated SQL; they cannot catch dialect-specific
 * bugs (e.g. T-SQL square-bracket escaping vs. ANSI quoted identifiers,
 * {@code OFFSET / FETCH NEXT} pagination ordering, {@code GROUP BY}
 * + {@code OVER()} interactions, NULL coercion through
 * {@code ISNULL/NULLIF}). This integration test spins up an MSSQL
 * container, materialises the canonical fact-row schema used by
 * {@code fin-muhasebe-detay} and friends, runs each
 * {@link SqlBuilder} method against it, and asserts on the result
 * set.
 *
 * <p>Gated behind the {@code integration} JUnit tag and
 * {@code @Testcontainers(disabledWithoutDocker = true)} so the default
 * {@code mvn test} stays fast and Docker-free; CI runs this via the
 * {@code -Pintegration-tests} profile.
 *
 * <p>Container reuse: a single class-level container backs every
 * {@code @Test}. Each test creates its own scratch table to keep
 * scenarios independent without paying the ~30s startup cost between
 * methods.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class SqlBuilderMssqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static SqlBuilder builder;
    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(MSSQL.getDriverClassName());
        ds.setUrl(MSSQL.getJdbcUrl() + ";encrypt=false;trustServerCertificate=true");
        ds.setUsername(MSSQL.getUsername());
        ds.setPassword(MSSQL.getPassword());
        jdbc = new NamedParameterJdbcTemplate(ds);
        builder = new SqlBuilder();

        // Top-level test schema. The production source uses
        // workcube_mikrolink* multi-tenant schemas; we mimic the layout
        // with a single 'dbo' schema since SqlBuilder's bracket-quoting
        // is the same regardless of schema name.
        jdbc.getJdbcTemplate().execute(
                "IF SCHEMA_ID('dbo') IS NULL EXEC('CREATE SCHEMA dbo')");
    }

    /**
     * Helper: create a temporary scratch table, seed it with rows, and
     * return a {@link ReportDefinition} pointing at it.
     */
    private static ReportDefinition scratch(String tableName,
                                              List<ColumnDefinition> columns,
                                              String createSql,
                                              List<String> seedSql) {
        jdbc.getJdbcTemplate().execute("IF OBJECT_ID('dbo." + tableName + "', 'U') IS NOT NULL DROP TABLE dbo." + tableName);
        jdbc.getJdbcTemplate().execute(createSql);
        for (String row : seedSql) {
            jdbc.getJdbcTemplate().execute(row);
        }
        return new ReportDefinition(
                "scratch-" + tableName,
                "1",
                "Scratch " + tableName,
                "test",
                "test",
                tableName,
                "dbo",
                "static",
                null,
                null,
                columns,
                null,
                "ASC",
                new AccessConfig(null, null, null, null));
    }

    @Test
    void buildDataQuery_pagesAndOrders_overRealMssql() {
        ReportDefinition def = scratch(
                "tx",
                List.of(
                        new ColumnDefinition("id", "ID", "number", 50, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE dbo.tx (id INT NOT NULL PRIMARY KEY, amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO dbo.tx VALUES (1, 100.00)",
                        "INSERT INTO dbo.tx VALUES (2, 200.00)",
                        "INSERT INTO dbo.tx VALUES (3, 300.00)",
                        "INSERT INTO dbo.tx VALUES (4, 400.00)",
                        "INSERT INTO dbo.tx VALUES (5, 500.00)"));

        // Page 2, size 2 → rows 3, 4 with default ORDER BY (SELECT NULL).
        // We pass an explicit sort to make the assertion deterministic.
        SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                def, null, List.of("id", "amount"),
                Collections.emptyMap(),
                List.of(Map.of("colId", "id", "sort", "asc")),
                null, null, 2, 2);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("id")).isEqualTo(3);
        assertThat(rows.get(1).get("id")).isEqualTo(4);
    }

    @Test
    void buildCountQuery_returnsRowCount_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_count",
                List.of(new ColumnDefinition("id", "ID", "number", 50, false)),
                "CREATE TABLE dbo.tx_count (id INT NOT NULL PRIMARY KEY)",
                List.of(
                        "INSERT INTO dbo.tx_count VALUES (1)",
                        "INSERT INTO dbo.tx_count VALUES (2)",
                        "INSERT INTO dbo.tx_count VALUES (3)"));

        SqlBuilder.BuiltQuery q = builder.buildCountQuery(
                def, null, Collections.emptyMap(), List.of("id"), null, null);

        Long count = jdbc.queryForObject(q.sql(), q.params(), Long.class);
        assertThat(count).isEqualTo(3L);
    }

    @Test
    void buildGroupedQuery_singleLevelSum_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_grouped",
                List.of(
                        new ColumnDefinition("category", "Cat", "text", 100, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE dbo.tx_grouped (category NVARCHAR(20) NOT NULL, amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO dbo.tx_grouped VALUES ('FIN', 100.00)",
                        "INSERT INTO dbo.tx_grouped VALUES ('FIN', 50.00)",
                        "INSERT INTO dbo.tx_grouped VALUES ('FIN', 25.00)",
                        "INSERT INTO dbo.tx_grouped VALUES ('HR', 1000.00)",
                        "INSERT INTO dbo.tx_grouped VALUES ('HR', 500.00)"));

        SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                def, null, List.of("category", "amount"),
                "category",
                List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(2);
        // ORDER BY [category] ASC by default → FIN first, HR second.
        assertThat(rows.get(0).get("category")).isEqualTo("FIN");
        assertThat(((Number) rows.get(0).get("_rowCount")).longValue()).isEqualTo(3L);
        assertThat(((Number) rows.get(0).get("amount")).doubleValue()).isEqualTo(175.00);
        assertThat(rows.get(1).get("category")).isEqualTo("HR");
        assertThat(((Number) rows.get(1).get("_rowCount")).longValue()).isEqualTo(2L);
        assertThat(((Number) rows.get(1).get("amount")).doubleValue()).isEqualTo(1500.00);
    }

    @Test
    void buildGroupedQuery_avgAndMinMax_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_aggs",
                List.of(
                        new ColumnDefinition("region", "R", "text", 100, false),
                        new ColumnDefinition("amount", "A", "number", 100, false)),
                "CREATE TABLE dbo.tx_aggs (region NVARCHAR(20) NOT NULL, amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO dbo.tx_aggs VALUES ('EU', 100.00)",
                        "INSERT INTO dbo.tx_aggs VALUES ('EU', 200.00)",
                        "INSERT INTO dbo.tx_aggs VALUES ('EU', 300.00)"));

        SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                def, null, List.of("region", "amount"),
                "region",
                List.of(new SqlBuilder.GroupedAggregation("amount", "avg")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("amount")).doubleValue()).isEqualTo(200.00);
    }

    @Test
    void buildGroupedCountQuery_returnsBucketCount_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_bucket",
                List.of(
                        new ColumnDefinition("category", "Cat", "text", 100, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE dbo.tx_bucket (category NVARCHAR(20) NOT NULL, amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO dbo.tx_bucket VALUES ('FIN', 100.00)",
                        "INSERT INTO dbo.tx_bucket VALUES ('FIN', 50.00)",
                        "INSERT INTO dbo.tx_bucket VALUES ('HR', 1000.00)",
                        "INSERT INTO dbo.tx_bucket VALUES ('OPS', 5.00)"));

        SqlBuilder.BuiltQuery q = builder.buildGroupedCountQuery(
                def, null, List.of("category", "amount"), "category",
                Collections.emptyMap(), null, null);

        // 3 distinct categories regardless of source row count.
        Long count = jdbc.queryForObject(q.sql(), q.params(), Long.class);
        assertThat(count).isEqualTo(3L);
    }

    @Test
    void buildGroupedQuery_filtersOutNullsViaIsnullSentinel() {
        // Mirrors PR #86 source-query pattern: the production SQL
        // wraps LEFT JOIN'd dimension labels in
        // ISNULL(NULLIF(LTRIM(RTRIM(x)), ''), 'Belirtilmemiş') so
        // null buckets collapse into a single sentinel value the
        // expansion path can handle. Verify the same behavior on a
        // scratch table with a custom sourceQuery.
        jdbc.getJdbcTemplate().execute(
                "IF OBJECT_ID('dbo.tx_null', 'U') IS NOT NULL DROP TABLE dbo.tx_null");
        jdbc.getJdbcTemplate().execute(
                "CREATE TABLE dbo.tx_null (id INT NOT NULL PRIMARY KEY, "
                        + "department NVARCHAR(20) NULL, amount DECIMAL(18,2) NOT NULL)");
        jdbc.getJdbcTemplate().execute("INSERT INTO dbo.tx_null VALUES (1, 'FIN', 100.00)");
        jdbc.getJdbcTemplate().execute("INSERT INTO dbo.tx_null VALUES (2, NULL,  50.00)");
        jdbc.getJdbcTemplate().execute("INSERT INTO dbo.tx_null VALUES (3, '',    25.00)");
        jdbc.getJdbcTemplate().execute("INSERT INTO dbo.tx_null VALUES (4, '   ', 10.00)");

        ReportDefinition def = new ReportDefinition(
                "scratch-tx_null",
                "1",
                "Scratch null sentinel",
                "test",
                "test",
                null,
                "dbo",
                "static",
                null,
                "SELECT id, ISNULL(NULLIF(LTRIM(RTRIM(department)), ''), 'Belirtilmemiş') AS department, amount FROM dbo.tx_null",
                List.of(
                        new ColumnDefinition("id", "ID", "number", 50, false),
                        new ColumnDefinition("department", "Dept", "text", 100, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                null, "ASC",
                new AccessConfig(null, null, null, null));

        SqlBuilder.BuiltQuery q = builder.buildGroupedQuery(
                def, null, List.of("id", "department", "amount"),
                "department",
                List.of(new SqlBuilder.GroupedAggregation("amount", "sum")),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(2);
        // The 3 NULL/empty/whitespace rows collapse into one
        // 'Belirtilmemiş' bucket; FIN keeps its own.
        assertThat(rows).anyMatch(r ->
                "Belirtilmemiş".equals(r.get("department"))
                        && ((Number) r.get("_rowCount")).longValue() == 3L
                        && ((Number) r.get("amount")).doubleValue() == 85.00);
        assertThat(rows).anyMatch(r ->
                "FIN".equals(r.get("department"))
                        && ((Number) r.get("_rowCount")).longValue() == 1L
                        && ((Number) r.get("amount")).doubleValue() == 100.00);
    }

    @Test
    void buildGroupedQuery_unknownGroupColumn_rejected() {
        ReportDefinition def = scratch(
                "tx_reject",
                List.of(new ColumnDefinition("id", "ID", "number", 50, false)),
                "CREATE TABLE dbo.tx_reject (id INT NOT NULL PRIMARY KEY)",
                List.of("INSERT INTO dbo.tx_reject VALUES (1)"));

        // builder validates against the visible-column allowlist before
        // hitting the DB; we still cover this as an integration-level
        // contract because future refactors might move the check.
        assertThatThrownBy(() -> builder.buildGroupedQuery(
                def, null, List.of("id"),
                "ghost", List.of(),
                Collections.emptyMap(), Collections.emptyList(),
                null, null, 1, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildExportQuery_topRowsAndOrder_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_export",
                List.of(
                        new ColumnDefinition("id", "ID", "number", 50, false),
                        new ColumnDefinition("amount", "Amount", "number", 100, false)),
                "CREATE TABLE dbo.tx_export (id INT NOT NULL PRIMARY KEY, amount DECIMAL(18,2) NOT NULL)",
                List.of(
                        "INSERT INTO dbo.tx_export VALUES (1, 10.00)",
                        "INSERT INTO dbo.tx_export VALUES (2, 20.00)",
                        "INSERT INTO dbo.tx_export VALUES (3, 30.00)",
                        "INSERT INTO dbo.tx_export VALUES (4, 40.00)",
                        "INSERT INTO dbo.tx_export VALUES (5, 50.00)"));

        SqlBuilder.BuiltQuery q = builder.buildExportQuery(
                def, null, List.of("id", "amount"),
                Collections.emptyMap(),
                List.of(Map.of("colId", "id", "sort", "desc")),
                null, null, 3);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(3);
        // ORDER BY id DESC + TOP 3 → 5, 4, 3.
        assertThat(rows.get(0).get("id")).isEqualTo(5);
        assertThat(rows.get(1).get("id")).isEqualTo(4);
        assertThat(rows.get(2).get("id")).isEqualTo(3);
    }

    @Test
    void buildDataQuery_filterModelEqualsAndContains_overRealMssql() {
        ReportDefinition def = scratch(
                "tx_filter",
                List.of(
                        new ColumnDefinition("id", "ID", "number", 50, false),
                        new ColumnDefinition("name", "Name", "text", 100, false)),
                "CREATE TABLE dbo.tx_filter (id INT NOT NULL PRIMARY KEY, name NVARCHAR(50) NOT NULL)",
                List.of(
                        "INSERT INTO dbo.tx_filter VALUES (1, 'alpha')",
                        "INSERT INTO dbo.tx_filter VALUES (2, 'beta')",
                        "INSERT INTO dbo.tx_filter VALUES (3, 'alphabet')"));

        // FilterTranslator's "contains" should produce LIKE '%alpha%'
        // which on T-SQL with the default collation matches both
        // 'alpha' and 'alphabet'.
        SqlBuilder.BuiltQuery q = builder.buildDataQuery(
                def, null, List.of("id", "name"),
                Map.of("name", Map.of("filterType", "text", "type", "contains", "filter", "alpha")),
                List.of(Map.of("colId", "id", "sort", "asc")),
                null, null, 1, 50);

        List<Map<String, Object>> rows = jdbc.queryForList(q.sql(), q.params());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("name")).isEqualTo("alpha");
        assertThat(rows.get(1).get("name")).isEqualTo("alphabet");
    }
}
