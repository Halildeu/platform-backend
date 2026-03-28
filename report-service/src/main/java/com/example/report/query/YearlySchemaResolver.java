package com.example.report.query;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.ReportDefinition;
import java.time.LocalDate;
import java.time.Year;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves year-based schema names for Workcube multi-tenant structure.
 * Pattern: workcube_mikrolink_{YYYY}_{companyId}
 *
 * Caches available schema names from sys.schemas to avoid repeated lookups.
 * Extracts date ranges from AG Grid filters to determine which year schemas to query.
 */
@Component
public class YearlySchemaResolver {

    private static final Logger log = LoggerFactory.getLogger(YearlySchemaResolver.class);

    private final NamedParameterJdbcTemplate jdbc;

    public YearlySchemaResolver(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All resolved schemas for a yearly report, given the filter context. */
    public record ResolvedSchemas(List<String> schemas) {
        public boolean isSingle() {
            return schemas.size() == 1;
        }
    }

    /**
     * Resolve which year schemas to query for a yearly report.
     *
     * @param def           report definition (must have isYearlySchema() == true)
     * @param authz         user's authz context (for extracting companyId from COMPANY scope)
     * @param agGridFilter  AG Grid filter model (may contain date range on yearColumn)
     * @return resolved schema names that actually exist in the database
     */
    public ResolvedSchemas resolve(ReportDefinition def, AuthzMeResponse authz,
                                   Map<String, Object> agGridFilter) {
        if (!def.isYearlySchema()) {
            return new ResolvedSchemas(List.of(def.sourceSchema()));
        }

        // Extract company IDs from RLS scope
        Set<String> companyIds = authz != null ? authz.getScopeRefIds("COMPANY") : Set.of();
        if (companyIds.isEmpty()) {
            // No COMPANY scope — try to extract company ID from sourceSchema pattern
            // e.g. "workcube_mikrolink_1" → company 1
            String companyFromSchema = extractCompanyFromSchema(def.sourceSchema());
            if (companyFromSchema != null) {
                companyIds = Set.of(companyFromSchema);
                log.debug("Inferred company {} from sourceSchema for report {}", companyFromSchema, def.key());
            } else {
                log.warn("No COMPANY scope for yearly report {}, using base schema", def.key());
                return new ResolvedSchemas(List.of(def.sourceSchema()));
            }
        }

        // Extract year range from date filters
        int[] yearRange = extractYearRange(def.yearColumn(), agGridFilter);
        int startYear = yearRange[0];
        int endYear = yearRange[1];

        // Get all available schemas from cache
        Set<String> available = getAvailableSchemas();

        // Build schema list: for each company x each year, check if schema exists
        List<String> resolved = new ArrayList<>();
        for (String companyId : companyIds) {
            for (int year = startYear; year <= endYear; year++) {
                String schema = "workcube_mikrolink_" + year + "_" + companyId;
                if (available.contains(schema.toLowerCase(Locale.ROOT))) {
                    resolved.add(schema);
                } else {
                    log.debug("Schema not found: {}", schema);
                }
            }
        }

        if (resolved.isEmpty()) {
            log.warn("No year schemas found for report {} (years {}-{}, companies {}), falling back to base",
                    def.key(), startYear, endYear, companyIds);
            return new ResolvedSchemas(List.of(def.sourceSchema()));
        }

        log.debug("Resolved {} schemas for report {}: {}", resolved.size(), def.key(), resolved);
        return new ResolvedSchemas(resolved);
    }

    /**
     * Extract year range from AG Grid date filters on the yearColumn.
     * Returns [startYear, endYear]. Defaults to current year if no date filter found.
     */
    private int[] extractYearRange(String yearColumn, Map<String, Object> agGridFilter) {
        int currentYear = Year.now().getValue();

        if (yearColumn == null || yearColumn.isBlank() || agGridFilter == null || agGridFilter.isEmpty()) {
            // No date column or no filters — default to current year only
            return new int[]{currentYear, currentYear};
        }

        Object filterModel = agGridFilter.get(yearColumn);
        if (!(filterModel instanceof Map<?, ?> filterMap)) {
            // No filter on yearColumn — check all date-type filters for year hints
            return extractYearRangeFromAnyDateFilter(agGridFilter, currentYear);
        }

        return extractYearFromFilterMap(filterMap, currentYear);
    }

    @SuppressWarnings("unchecked")
    private int[] extractYearFromFilterMap(Map<?, ?> filterMap, int currentYear) {
        String type = (String) filterMap.get("type");
        if (type == null) {
            return new int[]{currentYear, currentYear};
        }

        return switch (type) {
            case "inRange" -> {
                int fromYear = parseYearFromDateString(filterMap.get("filter"), currentYear);
                int toYear = parseYearFromDateString(filterMap.get("filterTo"), currentYear);
                yield new int[]{Math.min(fromYear, toYear), Math.max(fromYear, toYear)};
            }
            case "equals" -> {
                int year = parseYearFromDateString(filterMap.get("filter"), currentYear);
                yield new int[]{year, year};
            }
            case "greaterThan", "greaterThanOrEqual" -> {
                int fromYear = parseYearFromDateString(filterMap.get("filter"), currentYear);
                yield new int[]{fromYear, currentYear};
            }
            case "lessThan", "lessThanOrEqual" -> {
                int toYear = parseYearFromDateString(filterMap.get("filter"), currentYear);
                // Go back max 5 years for open-ended "less than" filters
                yield new int[]{Math.max(toYear - 5, 2020), toYear};
            }
            case "notBlank" -> {
                // All data — go back 5 years
                yield new int[]{currentYear - 5, currentYear};
            }
            default -> new int[]{currentYear, currentYear};
        };
    }

    /**
     * If no filter on yearColumn specifically, scan all date filters for year hints.
     */
    private int[] extractYearRangeFromAnyDateFilter(Map<String, Object> agGridFilter, int currentYear) {
        int minYear = currentYear;
        int maxYear = currentYear;

        for (Map.Entry<String, Object> entry : agGridFilter.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> filterMap)) continue;
            String filterType = (String) filterMap.get("filterType");
            if (!"date".equals(filterType)) continue;

            int[] range = extractYearFromFilterMap(filterMap, currentYear);
            minYear = Math.min(minYear, range[0]);
            maxYear = Math.max(maxYear, range[1]);
        }

        return new int[]{minYear, maxYear};
    }

    /**
     * Parse year from an AG Grid date filter value.
     * AG Grid sends dates as "YYYY-MM-DD" strings.
     */
    private int parseYearFromDateString(Object dateValue, int fallback) {
        if (dateValue == null) return fallback;
        String s = dateValue.toString().trim();
        if (s.length() >= 4) {
            try {
                return Integer.parseInt(s.substring(0, 4));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return fallback;
    }

    /**
     * Cached: returns all schema names in the database (lowercase).
     * Queried from sys.schemas which is very fast on SQL Server.
     */
    /**
     * Extract company ID from sourceSchema pattern.
     * "workcube_mikrolink_1" → "1", "workcube_mikrolink_2" → "2"
     * Returns null if pattern doesn't match.
     */
    private String extractCompanyFromSchema(String sourceSchema) {
        if (sourceSchema == null) return null;
        // Pattern: workcube_mikrolink_{companyId} (no year part)
        String prefix = "workcube_mikrolink_";
        if (!sourceSchema.startsWith(prefix)) return null;
        String suffix = sourceSchema.substring(prefix.length());
        // Should be just a number (company ID), not year_company pattern
        if (suffix.matches("\\d+")) {
            return suffix;
        }
        return null;
    }

    @Cacheable(value = "yearlySchemas", key = "'all'")
    public Set<String> getAvailableSchemas() {
        log.info("Loading available schemas from sys.schemas...");
        List<String> schemas = jdbc.getJdbcTemplate().queryForList(
                "SELECT name FROM sys.schemas WHERE name LIKE 'workcube_mikrolink%'",
                String.class);
        Set<String> result = schemas.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        log.info("Found {} workcube schemas", result.size());
        return result;
    }
}
