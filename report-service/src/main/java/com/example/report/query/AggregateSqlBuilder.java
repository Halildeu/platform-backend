package com.example.report.query;

import com.example.report.model.FilterColumnSpec;
import com.example.report.registry.AggregateSpec;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AggregateSqlBuilder {

    public record BuiltQuery(String sql, MapSqlParameterSource params) {}

    /**
     * Original method — delegates to the filter-aware overload with empty filter maps.
     */
    public BuiltQuery buildAggregateQuery(String source,
                                           String sourceSchema,
                                           AggregateSpec aggregate,
                                           String rlsWhereClause,
                                           MapSqlParameterSource rlsParams,
                                           MapSqlParameterSource timeParams) {
        return buildAggregateQuery(source, sourceSchema, aggregate,
                rlsWhereClause, rlsParams, timeParams, Map.of(), Map.of());
    }

    /**
     * Filter-aware query builder. Each entry in filterValues that has a matching
     * key in filterColumns produces a named-parameter WHERE clause and optional JOIN.
     */
    public BuiltQuery buildAggregateQuery(String source,
                                           String sourceSchema,
                                           AggregateSpec aggregate,
                                           String rlsWhereClause,
                                           MapSqlParameterSource rlsParams,
                                           MapSqlParameterSource timeParams,
                                           Map<String, FilterColumnSpec> filterColumns,
                                           Map<String, String> filterValues) {
        StringBuilder sql = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();

        sql.append("SELECT ").append(aggregate.select());
        sql.append(" FROM [").append(sourceSchema).append("].[").append(source).append("] WITH (NOLOCK)");

        String resolvedJoin = aggregate.join();
        if (resolvedJoin != null && !resolvedJoin.isBlank()
                && filterColumns != null && filterValues != null && !filterValues.isEmpty()) {
            resolvedJoin = injectFiltersIntoSubqueries(resolvedJoin, filterColumns, filterValues);
        }
        if (resolvedJoin != null && !resolvedJoin.isBlank()) {
            sql.append(" ").append(resolvedJoin);
        }

        // Append filter JOINs (deduplicated against existing aggregate join)
        String existingJoin = aggregate.join() != null ? aggregate.join() : "";
        if (filterColumns != null && filterValues != null) {
            for (Map.Entry<String, String> entry : filterValues.entrySet()) {
                FilterColumnSpec spec = filterColumns.get(entry.getKey());
                if (spec == null) continue;
                if (spec.join() != null && !spec.join().isBlank()) {
                    // Deduplicate: extract table name from JOIN and check existing
                    String filterJoin = spec.join().trim();
                    String joinTableToken = extractJoinTable(filterJoin);
                    if (joinTableToken != null && !existingJoin.contains(joinTableToken)
                            && !sql.toString().contains(joinTableToken)) {
                        sql.append(" ").append(filterJoin);
                    }
                }
            }
        }

        sql.append(" WHERE 1=1");

        if (rlsWhereClause != null && !rlsWhereClause.isBlank()) {
            sql.append(" AND ").append(rlsWhereClause);
            if (rlsParams != null) {
                mergeParams(params, rlsParams);
            }
        }

        if (aggregate.where() != null && !aggregate.where().isBlank()) {
            sql.append(" AND ").append(aggregate.where());
        }

        // Append filter WHERE clauses with named parameters
        if (filterColumns != null && filterValues != null) {
            for (Map.Entry<String, String> entry : filterValues.entrySet()) {
                FilterColumnSpec spec = filterColumns.get(entry.getKey());
                if (spec == null || spec.expression() == null) continue;
                String paramName = "_f_" + entry.getKey();
                sql.append(" AND ").append(spec.expression()).append(" = :").append(paramName);
                if ("int".equalsIgnoreCase(spec.paramType())) {
                    params.addValue(paramName, Integer.parseInt(entry.getValue()));
                } else {
                    params.addValue(paramName, entry.getValue());
                }
            }
        }

        if (timeParams != null) {
            mergeParams(params, timeParams);
        }

        if (aggregate.groupBy() != null && !aggregate.groupBy().isBlank()) {
            sql.append(" GROUP BY ").append(aggregate.groupBy());
        }

        if (aggregate.orderBy() != null && !aggregate.orderBy().isBlank()) {
            sql.append(" ORDER BY ").append(aggregate.orderBy());
        }

        if (aggregate.limit() != null && aggregate.limit() > 0) {
            if (aggregate.orderBy() == null || aggregate.orderBy().isBlank()) {
                sql.append(" ORDER BY (SELECT NULL)");
            }
            sql.append(" OFFSET 0 ROWS FETCH NEXT :_aggLimit ROWS ONLY");
            params.addValue("_aggLimit", aggregate.limit());
        }

        return new BuiltQuery(sql.toString(), params);
    }

    /**
     * Injects filter conditions into subquery WHERE clauses within a JOIN string.
     * Subqueries (CROSS JOIN, inline CTEs) have their own scope and don't see
     * outer WHERE filters. This method finds each WHERE inside the join clause
     * and prepends filter conditions + JOINs so subquery results respect filters.
     */
    String injectFiltersIntoSubqueries(String joinClause,
                                       Map<String, FilterColumnSpec> filterColumns,
                                       Map<String, String> filterValues) {
        if (joinClause == null || filterValues == null || filterValues.isEmpty()) {
            return joinClause;
        }

        String upper = joinClause.toUpperCase();
        if (!upper.contains("(SELECT")) {
            return joinClause;
        }

        // Build filter fragments from active filter values
        StringBuilder filterWhere = new StringBuilder();
        List<FilterJoinEntry> filterJoins = new ArrayList<>();

        for (Map.Entry<String, String> entry : filterValues.entrySet()) {
            FilterColumnSpec spec = filterColumns.get(entry.getKey());
            if (spec == null || spec.expression() == null) continue;

            String paramName = "_f_" + entry.getKey();
            filterWhere.append(" AND ").append(spec.expression()).append(" = :").append(paramName);

            if (spec.join() != null && !spec.join().isBlank()) {
                String joinTable = extractJoinTable(spec.join().trim());
                filterJoins.add(new FilterJoinEntry(spec.join().trim(), joinTable));
            }
        }

        if (filterWhere.isEmpty()) {
            return joinClause;
        }

        // Find all " WHERE " positions (case-insensitive) — these are subquery WHEREs
        List<Integer> wherePositions = new ArrayList<>();
        String lowerJoin = joinClause.toLowerCase();
        int searchFrom = 0;
        while (true) {
            int pos = lowerJoin.indexOf(" where ", searchFrom);
            if (pos == -1) break;
            wherePositions.add(pos);
            searchFrom = pos + 7;
        }

        if (wherePositions.isEmpty()) {
            return joinClause;
        }

        // Process right-to-left to preserve indices
        StringBuilder result = new StringBuilder(joinClause);
        for (int i = wherePositions.size() - 1; i >= 0; i--) {
            int wherePos = wherePositions.get(i);
            int afterWhere = wherePos + 7; // position after " WHERE "

            // Insert filter WHERE conditions after "WHERE "
            String whereInject = "1=1" + filterWhere + " AND ";
            result.insert(afterWhere, whereInject);

            // Insert filter JOINs before " WHERE " (with dedup per subquery scope)
            if (!filterJoins.isEmpty()) {
                String textBeforeWhere = result.substring(0, wherePos).toUpperCase();
                // Narrow to current subquery: find nearest "(SELECT" or "UNION ALL" boundary
                int subqStart = Math.max(
                        textBeforeWhere.lastIndexOf("(SELECT"),
                        textBeforeWhere.lastIndexOf("UNION ALL"));
                String subqueryScope = subqStart >= 0
                        ? textBeforeWhere.substring(subqStart) : textBeforeWhere;

                StringBuilder joinInject = new StringBuilder();
                for (FilterJoinEntry fj : filterJoins) {
                    if (fj.tableToken != null
                            && subqueryScope.contains(fj.tableToken.toUpperCase())) {
                        continue; // table already joined in this subquery
                    }
                    joinInject.append(" ").append(fj.joinClause);
                }
                if (!joinInject.isEmpty()) {
                    result.insert(wherePos, joinInject);
                }
            }
        }

        return result.toString();
    }

    private record FilterJoinEntry(String joinClause, String tableToken) {}

    /**
     * Extracts the bracketed table reference from a JOIN clause for deduplication.
     * E.g. "INNER JOIN [schema].[TABLE] alias ON ..." returns "[schema].[TABLE]".
     */
    private static String extractJoinTable(String joinClause) {
        // Match pattern like [schema].[table]
        int firstBracket = joinClause.indexOf('[');
        if (firstBracket < 0) return null;
        // Find end of second bracket pair: [schema].[table]
        int closeBracket1 = joinClause.indexOf(']', firstBracket);
        if (closeBracket1 < 0) return null;
        int dot = joinClause.indexOf('.', closeBracket1);
        if (dot < 0) return joinClause.substring(firstBracket, closeBracket1 + 1);
        int openBracket2 = joinClause.indexOf('[', dot);
        if (openBracket2 < 0) return joinClause.substring(firstBracket, closeBracket1 + 1);
        int closeBracket2 = joinClause.indexOf(']', openBracket2);
        if (closeBracket2 < 0) return joinClause.substring(firstBracket, closeBracket1 + 1);
        return joinClause.substring(firstBracket, closeBracket2 + 1);
    }

    public static MapSqlParameterSource buildTimeParams(String timeRange) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime periodStart = resolveStart(timeRange, now);

        params.addValue("_periodStart", periodStart);
        params.addValue("_periodEnd", now);

        long days = ChronoUnit.DAYS.between(periodStart, now);
        LocalDateTime prevPeriodEnd = periodStart.minusDays(1);
        LocalDateTime prevPeriodStart = prevPeriodEnd.minusDays(days);

        params.addValue("_prevPeriodStart", prevPeriodStart);
        params.addValue("_prevPeriodEnd", prevPeriodEnd);
        params.addValue("_trendPeriodStart", prevPeriodStart);

        return params;
    }

    private static LocalDateTime resolveStart(String timeRange, LocalDateTime now) {
        if (timeRange == null) {
            return now.minusDays(90);
        }
        return switch (timeRange.toLowerCase()) {
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            case "90d" -> now.minusDays(90);
            case "180d" -> now.minusDays(180);
            case "1y" -> now.minusYears(1);
            case "ytd" -> LocalDate.now().atStartOfDay().withDayOfYear(1);
            default -> now.minusDays(90);
        };
    }

    private void mergeParams(MapSqlParameterSource target, MapSqlParameterSource source) {
        source.getValues().forEach(target::addValue);
    }
}
