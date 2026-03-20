package com.example.report.query;

import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

public class SqlBuilder {

    public record BuiltQuery(String sql, MapSqlParameterSource params) {}

    private final FilterTranslator filterTranslator = new FilterTranslator();
    private final SortTranslator sortTranslator = new SortTranslator();

    // ── Single-schema queries (original behavior) ──────────────────────

    public BuiltQuery buildDataQuery(ReportDefinition def,
                                      List<String> visibleColumns,
                                      Map<String, Object> agGridFilter,
                                      List<Map<String, String>> sortModel,
                                      String rlsWhereClause,
                                      MapSqlParameterSource rlsParams,
                                      int page,
                                      int pageSize) {
        return buildDataQuery(def, null, visibleColumns, agGridFilter, sortModel,
                rlsWhereClause, rlsParams, page, pageSize);
    }

    public BuiltQuery buildCountQuery(ReportDefinition def,
                                       Map<String, Object> agGridFilter,
                                       List<String> visibleColumns,
                                       String rlsWhereClause,
                                       MapSqlParameterSource rlsParams) {
        return buildCountQuery(def, null, agGridFilter, visibleColumns,
                rlsWhereClause, rlsParams);
    }

    public BuiltQuery buildExportQuery(ReportDefinition def,
                                        List<String> visibleColumns,
                                        Map<String, Object> agGridFilter,
                                        List<Map<String, String>> sortModel,
                                        String rlsWhereClause,
                                        MapSqlParameterSource rlsParams,
                                        int maxRows) {
        return buildExportQuery(def, null, visibleColumns, agGridFilter, sortModel,
                rlsWhereClause, rlsParams, maxRows);
    }

    // ── Multi-schema (UNION ALL) queries ───────────────────────────────

    public BuiltQuery buildDataQuery(ReportDefinition def,
                                      YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
                                      List<String> visibleColumns,
                                      Map<String, Object> agGridFilter,
                                      List<Map<String, String>> sortModel,
                                      String rlsWhereClause,
                                      MapSqlParameterSource rlsParams,
                                      int page,
                                      int pageSize) {
        Set<String> allowedCols = Set.copyOf(visibleColumns);
        String selectCols = visibleColumns.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", "));

        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult = filterTranslator.translate(agGridFilter, allowedCols);

        String fromClause = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(selectCols);
        sql.append(" FROM ").append(fromClause);

        String orderBy = sortTranslator.translate(sortModel, allowedCols, def.defaultSort(), def.defaultSortDirection());
        if (orderBy != null) {
            sql.append(" ORDER BY ").append(orderBy);
        } else {
            sql.append(" ORDER BY (SELECT NULL)");
        }

        int offset = (page - 1) * pageSize;
        sql.append(" OFFSET :_offset ROWS FETCH NEXT :_pageSize ROWS ONLY");
        params.addValue("_offset", offset);
        params.addValue("_pageSize", pageSize);

        return new BuiltQuery(sql.toString(), params);
    }

    public BuiltQuery buildCountQuery(ReportDefinition def,
                                       YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
                                       Map<String, Object> agGridFilter,
                                       List<String> visibleColumns,
                                       String rlsWhereClause,
                                       MapSqlParameterSource rlsParams) {
        Set<String> allowedCols = Set.copyOf(visibleColumns);
        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult = filterTranslator.translate(agGridFilter, allowedCols);

        // For count, we just need * from the union
        String fromClause = buildFromClause(def, resolvedSchemas, "*",
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) FROM ").append(fromClause);

        return new BuiltQuery(sql.toString(), params);
    }

    public BuiltQuery buildExportQuery(ReportDefinition def,
                                        YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
                                        List<String> visibleColumns,
                                        Map<String, Object> agGridFilter,
                                        List<Map<String, String>> sortModel,
                                        String rlsWhereClause,
                                        MapSqlParameterSource rlsParams,
                                        int maxRows) {
        Set<String> allowedCols = Set.copyOf(visibleColumns);
        String selectCols = visibleColumns.stream()
                .map(c -> "[" + c + "]")
                .collect(Collectors.joining(", "));

        MapSqlParameterSource params = new MapSqlParameterSource();
        FilterTranslator.FilterResult filterResult = filterTranslator.translate(agGridFilter, allowedCols);

        String fromClause = buildFromClause(def, resolvedSchemas, selectCols,
                rlsWhereClause, rlsParams, filterResult, params);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT TOP(:_maxRows) ").append(selectCols);
        sql.append(" FROM ").append(fromClause);
        params.addValue("_maxRows", maxRows);

        String orderBy = sortTranslator.translate(sortModel, allowedCols, def.defaultSort(), def.defaultSortDirection());
        if (orderBy != null) {
            sql.append(" ORDER BY ").append(orderBy);
        }

        return new BuiltQuery(sql.toString(), params);
    }

    // ── Internal helpers ───────────────────────────────────────────────

    /**
     * Builds the FROM clause. For single-schema or non-yearly reports, returns:
     *   [schema].[table] WITH (NOLOCK) WHERE 1=1 AND {rls} AND {filters}
     *
     * For multi-year schemas, returns a subquery with UNION ALL:
     *   (SELECT cols FROM [schema1].[table] WITH (NOLOCK) WHERE 1=1 AND {rls} AND {filters}
     *    UNION ALL
     *    SELECT cols FROM [schema2].[table] WITH (NOLOCK) WHERE 1=1 AND {rls} AND {filters}
     *   ) AS _u
     *
     * WHERE push-down: filters and RLS are applied inside each UNION branch for performance.
     */
    private String buildFromClause(ReportDefinition def,
                                   YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
                                   String selectCols,
                                   String rlsWhereClause,
                                   MapSqlParameterSource rlsParams,
                                   FilterTranslator.FilterResult filterResult,
                                   MapSqlParameterSource params) {

        boolean isMultiSchema = resolvedSchemas != null && !resolvedSchemas.isSingle();

        if (!isMultiSchema) {
            // Single schema — original flat query (no subquery overhead)
            String schema = (resolvedSchemas != null && !resolvedSchemas.schemas().isEmpty())
                    ? resolvedSchemas.schemas().get(0)
                    : def.sourceSchema();

            StringBuilder sb = new StringBuilder();
            sb.append("[").append(schema).append("].[").append(def.source()).append("] WITH (NOLOCK)");
            sb.append(" WHERE 1=1");
            appendWhereFilters(sb, rlsWhereClause, rlsParams, filterResult, params);
            return sb.toString();
        }

        // Multi-schema UNION ALL — wrap in subquery
        StringBuilder union = new StringBuilder();
        union.append("(\n");

        List<String> schemas = resolvedSchemas.schemas();
        for (int i = 0; i < schemas.size(); i++) {
            if (i > 0) {
                union.append("\n  UNION ALL\n");
            }
            union.append("  SELECT ").append(selectCols);
            union.append(" FROM [").append(schemas.get(i)).append("].[").append(def.source()).append("] WITH (NOLOCK)");
            union.append(" WHERE 1=1");
            // Push down RLS and filters into each branch
            appendWhereFiltersInline(union, rlsWhereClause, filterResult);
        }

        union.append("\n) AS _u");

        // Merge params once (same params apply to all branches via named parameters)
        if (rlsParams != null) {
            mergeParams(params, rlsParams);
        }
        if (!filterResult.whereClause().isBlank()) {
            mergeParams(params, filterResult.params());
        }

        return union.toString();
    }

    /** Append WHERE fragments and merge params (for single-schema path). */
    private void appendWhereFilters(StringBuilder sql,
                                    String rlsWhereClause,
                                    MapSqlParameterSource rlsParams,
                                    FilterTranslator.FilterResult filterResult,
                                    MapSqlParameterSource params) {
        if (rlsWhereClause != null && !rlsWhereClause.isBlank()) {
            sql.append(" AND ").append(rlsWhereClause);
            if (rlsParams != null) {
                mergeParams(params, rlsParams);
            }
        }
        if (!filterResult.whereClause().isBlank()) {
            sql.append(" AND ").append(filterResult.whereClause());
            mergeParams(params, filterResult.params());
        }
    }

    /** Append WHERE fragments inline (no param merge — done once for UNION). */
    private void appendWhereFiltersInline(StringBuilder sql,
                                          String rlsWhereClause,
                                          FilterTranslator.FilterResult filterResult) {
        if (rlsWhereClause != null && !rlsWhereClause.isBlank()) {
            sql.append(" AND ").append(rlsWhereClause);
        }
        if (!filterResult.whereClause().isBlank()) {
            sql.append(" AND ").append(filterResult.whereClause());
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeParams(MapSqlParameterSource target, MapSqlParameterSource source) {
        Map<String, Object> sourceValues = source.getValues();
        sourceValues.forEach(target::addValue);
    }
}
