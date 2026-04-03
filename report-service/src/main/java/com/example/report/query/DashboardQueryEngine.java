package com.example.report.query;

import com.example.report.access.RowFilterInjector;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.dto.ChartResultDto;
import com.example.report.dto.KpiResultDto;
import com.example.report.model.FilterColumnSpec;
import com.example.report.registry.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class DashboardQueryEngine {

    private static final Logger log = LoggerFactory.getLogger(DashboardQueryEngine.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final RowFilterInjector rowFilterInjector;
    private final AggregateSqlBuilder sqlBuilder = new AggregateSqlBuilder();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DashboardQueryEngine(NamedParameterJdbcTemplate jdbc,
                                 RowFilterInjector rowFilterInjector) {
        this.jdbc = jdbc;
        this.rowFilterInjector = rowFilterInjector;
    }

    /** Backward-compatible overload (no filters). */
    public List<KpiResultDto> executeKpis(DashboardDefinition dashboard,
                                           AuthzMeResponse authz,
                                           String timeRange) {
        return executeKpis(dashboard, authz, timeRange, Map.of());
    }

    public List<KpiResultDto> executeKpis(DashboardDefinition dashboard,
                                           AuthzMeResponse authz,
                                           String timeRange,
                                           Map<String, String> filterValues) {
        MapSqlParameterSource timeParams = AggregateSqlBuilder.buildTimeParams(timeRange);
        Map<String, FilterColumnSpec> filterColumns = dashboard.filterColumns();

        List<CompletableFuture<KpiResultDto>> futures = dashboard.kpis().stream()
                .map(kpi -> CompletableFuture.supplyAsync(
                        () -> executeKpi(kpi, dashboard.access(), authz, timeParams, filterColumns, filterValues), executor))
                .toList();

        return futures.stream()
                .map(f -> {
                    try {
                        return f.join();
                    } catch (Exception e) {
                        log.error("KPI execution failed: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /** Backward-compatible overload (no filters). */
    public List<ChartResultDto> executeCharts(DashboardDefinition dashboard,
                                               AuthzMeResponse authz,
                                               String timeRange) {
        return executeCharts(dashboard, authz, timeRange, Map.of());
    }

    public List<ChartResultDto> executeCharts(DashboardDefinition dashboard,
                                               AuthzMeResponse authz,
                                               String timeRange,
                                               Map<String, String> filterValues) {
        MapSqlParameterSource timeParams = AggregateSqlBuilder.buildTimeParams(timeRange);
        Map<String, FilterColumnSpec> filterColumns = dashboard.filterColumns();

        List<CompletableFuture<ChartResultDto>> futures = dashboard.charts().stream()
                .map(chart -> CompletableFuture.supplyAsync(
                        () -> executeChart(chart, dashboard.access(), authz, timeParams, filterColumns, filterValues), executor))
                .toList();

        return futures.stream()
                .map(f -> {
                    try {
                        return f.join();
                    } catch (Exception e) {
                        log.error("Chart execution failed: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public List<String> executeFilterOptions(DashboardDefinition dashboard,
                                              AuthzMeResponse authz,
                                              String filterKey) {
        FilterColumnSpec spec = dashboard.filterColumns().get(filterKey);
        if (spec == null || spec.expression() == null) {
            return List.of();
        }

        // Use the first KPI's source table as the base table
        String source = dashboard.kpis().isEmpty() ? "EMPLOYEES_PUANTAJ_ROWS" : dashboard.kpis().get(0).source();
        String sourceSchema = dashboard.kpis().isEmpty() ? "workcube_mikrolink" : dashboard.kpis().get(0).sourceSchema();

        StringBuilder sql = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();

        sql.append("SELECT DISTINCT ").append(spec.expression()).append(" AS opt_value");
        sql.append(" FROM [").append(sourceSchema).append("].[").append(source).append("] WITH (NOLOCK)");

        if (spec.join() != null && !spec.join().isBlank()) {
            sql.append(" ").append(spec.join().trim());
        }

        RowFilterInjector.RlsResult rls = buildRls(source, dashboard.access(), authz);
        sql.append(" WHERE ").append(spec.expression()).append(" IS NOT NULL");
        sql.append(" AND RTRIM(CAST(").append(spec.expression()).append(" AS VARCHAR(500))) != ''");

        if (rls.whereClause() != null && !rls.whereClause().isBlank()) {
            sql.append(" AND ").append(rls.whereClause());
            if (rls.params() != null) {
                rls.params().getValues().forEach(params::addValue);
            }
        }

        sql.append(" ORDER BY opt_value");

        log.debug("Filter options query [{}]: {}", filterKey, sql);

        try {
            return jdbc.queryForList(sql.toString(), params, String.class);
        } catch (Exception e) {
            log.error("Failed to execute filter options for {}: {}", filterKey, e.getMessage());
            return List.of();
        }
    }

    private KpiResultDto executeKpi(KpiDefinition kpi,
                                     AccessConfig access,
                                     AuthzMeResponse authz,
                                     MapSqlParameterSource timeParams,
                                     Map<String, FilterColumnSpec> filterColumns,
                                     Map<String, String> filterValues) {
        try {
            RowFilterInjector.RlsResult rls = buildRls(kpi.source(), access, authz);

            AggregateSqlBuilder.BuiltQuery query = sqlBuilder.buildAggregateQuery(
                    kpi.source(), kpi.sourceSchema(), kpi.aggregate(),
                    rls.whereClause(), rls.params(), timeParams,
                    filterColumns, filterValues);

            log.debug("KPI query [{}]: {}", kpi.id(), query.sql());

            Object value = jdbc.queryForObject(query.sql(), query.params(), Object.class);

            // Compute trend if configured
            KpiResultDto.TrendDto trend = null;
            if (kpi.trend() != null && kpi.trend().select() != null) {
                trend = computeTrend(kpi, access, authz, timeParams, filterColumns, filterValues, value);
            }

            String tone = evaluateTone(kpi, value);
            String formattedValue = formatValue(value, kpi.format());

            return new KpiResultDto(
                    kpi.id(), kpi.title(), kpi.format(),
                    value, formattedValue, trend, tone,
                    kpi.benchmark(), kpi.drillTo());
        } catch (Exception e) {
            log.error("Failed to execute KPI {}: {}", kpi.id(), e.getMessage());
            return new KpiResultDto(
                    kpi.id(), kpi.title(), kpi.format(),
                    null, "—", null, "default",
                    kpi.benchmark(), kpi.drillTo());
        }
    }

    private ChartResultDto executeChart(ChartDefinition chart,
                                         AccessConfig access,
                                         AuthzMeResponse authz,
                                         MapSqlParameterSource timeParams,
                                         Map<String, FilterColumnSpec> filterColumns,
                                         Map<String, String> filterValues) {
        try {
            RowFilterInjector.RlsResult rls = buildRls(chart.source(), access, authz);

            AggregateSqlBuilder.BuiltQuery query = sqlBuilder.buildAggregateQuery(
                    chart.source(), chart.sourceSchema(), chart.aggregate(),
                    rls.whereClause(), rls.params(), timeParams,
                    filterColumns, filterValues);

            log.debug("Chart query [{}]: {}", chart.id(), query.sql());

            List<Map<String, Object>> data = jdbc.queryForList(query.sql(), query.params());

            return new ChartResultDto(
                    chart.id(), chart.title(), chart.chartType(), chart.size(),
                    data, chart.chartConfig(), chart.drillTo());
        } catch (Exception e) {
            log.error("Failed to execute chart {}: {}", chart.id(), e.getMessage());
            return new ChartResultDto(
                    chart.id(), chart.title(), chart.chartType(), chart.size(),
                    List.of(), chart.chartConfig(), chart.drillTo());
        }
    }

    private KpiResultDto.TrendDto computeTrend(KpiDefinition kpi,
                                                 AccessConfig access,
                                                 AuthzMeResponse authz,
                                                 MapSqlParameterSource timeParams,
                                                 Map<String, FilterColumnSpec> filterColumns,
                                                 Map<String, String> filterValues,
                                                 Object currentValue) {
        try {
            AggregateSpec trendAggregate = new AggregateSpec(
                    kpi.trend().select(),
                    kpi.trend().where(),
                    null, null, null, null);

            RowFilterInjector.RlsResult rls = buildRls(kpi.source(), access, authz);

            AggregateSqlBuilder.BuiltQuery trendQuery = sqlBuilder.buildAggregateQuery(
                    kpi.source(), kpi.sourceSchema(), trendAggregate,
                    rls.whereClause(), rls.params(), timeParams,
                    filterColumns, filterValues);

            Object previousValue = jdbc.queryForObject(trendQuery.sql(), trendQuery.params(), Object.class);

            if (currentValue == null || previousValue == null) {
                return null;
            }

            double current = toDouble(currentValue);
            double previous = toDouble(previousValue);

            if (previous == 0) {
                return new KpiResultDto.TrendDto("stable", 0);
            }

            double percentage = ((current - previous) / Math.abs(previous)) * 100;
            boolean higherIsBetter = "higher_is_better".equals(kpi.trend().direction());

            String direction;
            if (Math.abs(percentage) < 0.5) {
                direction = "stable";
            } else if (percentage > 0) {
                direction = higherIsBetter ? "up" : "down";
            } else {
                direction = higherIsBetter ? "down" : "up";
            }

            return new KpiResultDto.TrendDto(direction, Math.abs(Math.round(percentage * 10.0) / 10.0));
        } catch (Exception e) {
            log.warn("Failed to compute trend for KPI {}: {}", kpi.id(), e.getMessage());
            return null;
        }
    }

    private String evaluateTone(KpiDefinition kpi, Object value) {
        if (kpi.tone() != null && !kpi.tone().isBlank()) {
            return kpi.tone();
        }
        if (kpi.toneRules() == null || kpi.toneRules().isEmpty() || value == null) {
            return "default";
        }

        double numValue = toDouble(value);

        for (ToneRule rule : kpi.toneRules()) {
            if (evaluateCondition(rule.condition(), numValue)) {
                return rule.tone();
            }
        }
        return "default";
    }

    private boolean evaluateCondition(String condition, double value) {
        if (condition == null) return false;
        String trimmed = condition.trim();
        try {
            if (trimmed.startsWith(">=")) {
                return value >= Double.parseDouble(trimmed.substring(2).trim());
            } else if (trimmed.startsWith("<=")) {
                return value <= Double.parseDouble(trimmed.substring(2).trim());
            } else if (trimmed.startsWith(">")) {
                return value > Double.parseDouble(trimmed.substring(1).trim());
            } else if (trimmed.startsWith("<")) {
                return value < Double.parseDouble(trimmed.substring(1).trim());
            } else if (trimmed.startsWith("==") || trimmed.startsWith("=")) {
                String num = trimmed.startsWith("==") ? trimmed.substring(2).trim() : trimmed.substring(1).trim();
                return Math.abs(value - Double.parseDouble(num)) < 0.0001;
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid tone rule condition: {}", condition);
        }
        return false;
    }

    private String formatValue(Object value, String format) {
        if (value == null) return "—";
        try {
            double numValue = toDouble(value);
            return switch (format != null ? format : "number") {
                case "percent" -> new DecimalFormat("#0.0").format(numValue * 100) + "%";
                case "currency" -> new DecimalFormat("#,##0.00").format(numValue) + " ₺";
                case "decimal" -> new DecimalFormat("#,##0.0").format(numValue);
                default -> {
                    if (numValue == Math.floor(numValue) && !Double.isInfinite(numValue)) {
                        yield new DecimalFormat("#,##0").format((long) numValue);
                    }
                    yield new DecimalFormat("#,##0.0").format(numValue);
                }
            };
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private RowFilterInjector.RlsResult buildRls(String source, AccessConfig access, AuthzMeResponse authz) {
        if (access == null || access.rowFilter() == null) {
            return new RowFilterInjector.RlsResult(null, null);
        }
        // Create a minimal ReportDefinition to reuse RowFilterInjector
        ReportDefinition pseudoDef = new ReportDefinition(
                "dashboard-rls", "1.0", "RLS", "", "", source,
                access.rowFilter().column() != null ? "dbo" : "dbo",
                "static", null, null,
                List.of(new ColumnDefinition("_id", "_id", "number", 1, false)),
                "_id", "ASC", access);
        return rowFilterInjector.buildRlsClause(pseudoDef, authz);
    }
}
