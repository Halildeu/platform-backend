package com.example.report.query;

import com.example.report.access.RowFilterInjector;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.dto.ChartResultDto;
import com.example.report.dto.KpiResultDto;
import com.example.report.registry.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardQueryEngineTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock RowFilterInjector rowFilterInjector;

    private DashboardQueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = new DashboardQueryEngine(jdbc, rowFilterInjector);
    }

    // ── Fixtures ──────────────────────────────────────────────

    private static KpiDefinition kpi(String id, String format) {
        return new KpiDefinition(id, "KPI " + id, format, null,
                List.of(new ToneRule(">=90", "success"), new ToneRule("<50", "danger")),
                "EMPLOYEES", "dbo",
                new AggregateSpec("COUNT(*)", "STATUS='A'", null, null, null, null),
                null, null, null);
    }

    private static ChartDefinition chart(String id) {
        return new ChartDefinition(id, "Chart " + id, "bar", "md",
                "EMPLOYEES", "dbo",
                new AggregateSpec("COUNT(*)", "STATUS='A'", "DEPT", "DEPT", null, "10"),
                null, null);
    }

    private static DashboardDefinition dashboard(List<KpiDefinition> kpis, List<ChartDefinition> charts) {
        return new DashboardDefinition("dashboard", "test-dash", "1.0", "Test Dashboard",
                "desc", "hr", "users", null,
                List.of("30d", "90d"), "90d", kpis, charts, null, Map.of());
    }

    private static AuthzMeResponse authz() {
        var a = new AuthzMeResponse();
        a.setSuperAdmin(true);
        a.setPermissions(List.of());
        a.setReports(Map.of());
        return a;
    }

    private void mockRlsEmpty() {
        when(rowFilterInjector.buildRlsClause(any(), any()))
                .thenReturn(new RowFilterInjector.RlsResult(null, null));
    }

    // ── executeKpis ───────────────────────────────────────────

    @Nested
    @DisplayName("executeKpis")
    class ExecuteKpis {

        @Test
        @DisplayName("single KPI returns formatted result with tone")
        void singleKpi_formattedResult() {
            mockRlsEmpty();
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Object.class)))
                    .thenReturn(95);

            var result = engine.executeKpis(dashboard(List.of(kpi("kpi-1", "number")), List.of()), authz(), "90d");

            assertEquals(1, result.size());
            assertEquals("kpi-1", result.get(0).id());
            assertEquals(95, result.get(0).value());
            assertEquals("success", result.get(0).tone()); // 95 >= 90 → success
        }

        @Test
        @DisplayName("KPI with percent format")
        void percentFormat() {
            mockRlsEmpty();
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Object.class)))
                    .thenReturn(0.85);

            var result = engine.executeKpis(dashboard(List.of(kpi("pct", "percent")), List.of()), authz(), "90d");

            assertEquals(1, result.size());
            // Locale-agnostic: accept both "85.0%" and "85,0%"
            assertTrue(result.get(0).formattedValue().matches("85[.,]0%"));
        }

        @Test
        @DisplayName("KPI with currency format")
        void currencyFormat() {
            mockRlsEmpty();
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Object.class)))
                    .thenReturn(12345.67);

            var result = engine.executeKpis(dashboard(List.of(kpi("amt", "currency")), List.of()), authz(), "90d");

            assertEquals(1, result.size());
            // Locale-agnostic: check digits present regardless of separator
            assertTrue(result.get(0).formattedValue().contains("12") && result.get(0).formattedValue().contains("345"));
        }

        @Test
        @DisplayName("KPI with null value returns dash")
        void nullValue_dash() {
            mockRlsEmpty();
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Object.class)))
                    .thenReturn(null);

            var result = engine.executeKpis(dashboard(List.of(kpi("null-kpi", "number")), List.of()), authz(), "90d");

            assertEquals(1, result.size());
            assertEquals("—", result.get(0).formattedValue());
        }

        @Test
        @DisplayName("danger tone when value < 50")
        void dangerTone() {
            mockRlsEmpty();
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Object.class)))
                    .thenReturn(30);

            var result = engine.executeKpis(dashboard(List.of(kpi("low", "number")), List.of()), authz(), "90d");

            assertEquals("danger", result.get(0).tone()); // 30 < 50 → danger
        }

        @Test
        @DisplayName("default tone when no rules match")
        void defaultTone() {
            mockRlsEmpty();
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Object.class)))
                    .thenReturn(70); // 50 <= 70 < 90 → no rule matches

            var result = engine.executeKpis(dashboard(List.of(kpi("mid", "number")), List.of()), authz(), "90d");

            assertEquals("default", result.get(0).tone());
        }

        @Test
        @DisplayName("multiple KPIs execute in parallel")
        void multipleKpis() {
            mockRlsEmpty();
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Object.class)))
                    .thenReturn(100);

            var result = engine.executeKpis(
                    dashboard(List.of(kpi("k1", "number"), kpi("k2", "percent"), kpi("k3", "currency")), List.of()),
                    authz(), "30d");

            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("JDBC exception returns error KPI with dash value")
        void jdbcException_errorResult() {
            mockRlsEmpty();
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Object.class)))
                    .thenThrow(new RuntimeException("DB down"));

            var result = engine.executeKpis(dashboard(List.of(kpi("err", "number")), List.of()), authz(), "90d");

            assertEquals(1, result.size());
            assertEquals("—", result.get(0).formattedValue());
            assertEquals("default", result.get(0).tone());
        }

        @Test
        @DisplayName("backward-compatible overload without filters")
        void backwardCompatible() {
            mockRlsEmpty();
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Object.class)))
                    .thenReturn(50);

            var result = engine.executeKpis(dashboard(List.of(kpi("compat", "number")), List.of()), authz(), "90d");

            assertFalse(result.isEmpty());
        }
    }

    // ── executeCharts ─────────────────────────────────────────

    @Nested
    @DisplayName("executeCharts")
    class ExecuteCharts {

        @Test
        @DisplayName("chart returns data rows")
        void chartReturnsData() {
            mockRlsEmpty();
            when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                    .thenReturn(List.of(Map.of("DEPT", "HR", "CNT", 10), Map.of("DEPT", "IT", "CNT", 20)));

            var result = engine.executeCharts(dashboard(List.of(), List.of(chart("c1"))), authz(), "90d");

            assertEquals(1, result.size());
            assertEquals("c1", result.get(0).id());
            assertEquals(2, result.get(0).data().size());
        }

        @Test
        @DisplayName("chart JDBC error returns empty data")
        void chartError_emptyData() {
            mockRlsEmpty();
            when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                    .thenThrow(new RuntimeException("timeout"));

            var result = engine.executeCharts(dashboard(List.of(), List.of(chart("err-c"))), authz(), "90d");

            assertEquals(1, result.size());
            assertTrue(result.get(0).data().isEmpty());
        }

        @Test
        @DisplayName("multiple charts execute in parallel")
        void multipleCharts() {
            mockRlsEmpty();
            when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                    .thenReturn(List.of(Map.of("x", 1)));

            var result = engine.executeCharts(
                    dashboard(List.of(), List.of(chart("c1"), chart("c2"))), authz(), "90d");

            assertEquals(2, result.size());
        }
    }

    // ── executeFilterOptions ──────────────────────────────────

    @Nested
    @DisplayName("executeFilterOptions")
    class FilterOptions {

        @Test
        @DisplayName("unknown filter key returns empty")
        void unknownKey_empty() {
            var result = engine.executeFilterOptions(
                    dashboard(List.of(kpi("k1", "number")), List.of()),
                    authz(), "nonexistent");

            assertTrue(result.isEmpty());
        }
    }
}
