package com.example.report.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

public class FilterTranslator {

    private int paramCounter = 0;

    public record FilterResult(String whereClause, MapSqlParameterSource params) {}

    public FilterResult translate(Map<String, Object> agGridFilter, Set<String> allowedColumns) {
        if (agGridFilter == null || agGridFilter.isEmpty()) {
            return new FilterResult("", new MapSqlParameterSource());
        }

        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();

        for (Map.Entry<String, Object> entry : agGridFilter.entrySet()) {
            String column = entry.getKey();
            if (!allowedColumns.contains(column)) {
                continue;
            }

            Object filterModel = entry.getValue();
            if (filterModel instanceof Map<?, ?> filterMap) {
                String clause = translateSingleFilter(column, filterMap, params);
                if (clause != null && !clause.isBlank()) {
                    clauses.add(clause);
                }
            }
        }

        String combined = String.join(" AND ", clauses);
        return new FilterResult(combined, params);
    }

    @SuppressWarnings("unchecked")
    private String translateSingleFilter(String column, Map<?, ?> filterMap, MapSqlParameterSource params) {
        String filterType = (String) filterMap.get("filterType");
        String type = (String) filterMap.get("type");

        if ("set".equals(filterType)) {
            List<String> values = (List<String>) filterMap.get("values");
            if (values == null || values.isEmpty()) {
                return null;
            }
            String paramName = nextParam();
            params.addValue(paramName, values);
            return "[" + column + "] IN (:" + paramName + ")";
        }

        if (type == null) {
            return null;
        }

        return switch (type) {
            case "contains" -> {
                String p = nextParam();
                params.addValue(p, "%" + filterMap.get("filter") + "%");
                yield "[" + column + "] LIKE :" + p;
            }
            case "notContains" -> {
                String p = nextParam();
                params.addValue(p, "%" + filterMap.get("filter") + "%");
                yield "[" + column + "] NOT LIKE :" + p;
            }
            case "equals" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] = :" + p;
            }
            case "notEqual" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] <> :" + p;
            }
            case "startsWith" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter") + "%");
                yield "[" + column + "] LIKE :" + p;
            }
            case "endsWith" -> {
                String p = nextParam();
                params.addValue(p, "%" + filterMap.get("filter"));
                yield "[" + column + "] LIKE :" + p;
            }
            case "lessThan" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] < :" + p;
            }
            case "lessThanOrEqual" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] <= :" + p;
            }
            case "greaterThan" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] > :" + p;
            }
            case "greaterThanOrEqual" -> {
                String p = nextParam();
                params.addValue(p, filterMap.get("filter"));
                yield "[" + column + "] >= :" + p;
            }
            case "inRange" -> {
                String pFrom = nextParam();
                String pTo = nextParam();
                params.addValue(pFrom, filterMap.get("filter"));
                params.addValue(pTo, filterMap.get("filterTo"));
                yield "[" + column + "] BETWEEN :" + pFrom + " AND :" + pTo;
            }
            case "blank" -> "[" + column + "] IS NULL";
            case "notBlank" -> "[" + column + "] IS NOT NULL";
            default -> null;
        };
    }

    private String nextParam() {
        return "p" + (++paramCounter);
    }
}
