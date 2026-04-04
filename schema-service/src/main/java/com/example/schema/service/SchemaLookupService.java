package com.example.schema.service;

import com.example.schema.model.ColumnInfo;
import com.example.schema.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * FK Lookup Service — resolves foreign key IDs to human-readable display values.
 *
 * Given a table, list of IDs, and optional display column name,
 * returns a map of ID → display value (e.g., "1" → "Ahmet Yılmaz").
 */
@Service
public class SchemaLookupService {

    private static final Logger log = LoggerFactory.getLogger(SchemaLookupService.class);

    /** Max IDs per lookup request to prevent excessive queries */
    private static final int MAX_BATCH_SIZE = 500;

    /** Safe identifier pattern — prevents SQL injection in table/column names */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** Display column name candidates — tried in order when no explicit column specified */
    private static final List<String> DISPLAY_COLUMN_CANDIDATES = List.of(
            "NAME", "TITLE", "DESCRIPTION", "LABEL",
            "AD", "BASLIK", "TANIM", "ACIKLAMA",
            "FULL_NAME", "DISPLAY_NAME", "SHORT_NAME"
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final SchemaExtractService extractService;

    @Value("${schema.default-schema:workcube_mikrolink}")
    private String defaultSchema;

    public SchemaLookupService(NamedParameterJdbcTemplate jdbc,
                               SchemaExtractService extractService) {
        this.jdbc = jdbc;
        this.extractService = extractService;
    }

    /**
     * Lookup display values for a list of IDs.
     *
     * @param table      target table name
     * @param ids        list of primary key values to resolve
     * @param displayCol explicit display column name (nullable — auto-detected if absent)
     * @param schema     database schema (nullable — uses default)
     * @return lookup result with table, pkColumn, displayColumn, and values map
     */
    public Map<String, Object> lookupValues(String table, List<String> ids,
                                             String displayCol, String schema) {
        String targetSchema = schema != null ? schema : defaultSchema;

        // Validate table name
        if (!SAFE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }

        // Enforce batch limit
        List<String> limitedIds = ids.size() > MAX_BATCH_SIZE
                ? ids.subList(0, MAX_BATCH_SIZE)
                : ids;

        // Resolve table metadata to find PK and display columns
        Map<String, TableInfo> tables = extractService.extractTables(targetSchema);
        TableInfo tableInfo = tables.get(table.toUpperCase());
        if (tableInfo == null) {
            // Try case-insensitive match
            tableInfo = tables.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(table))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        if (tableInfo == null) {
            log.warn("Table not found for lookup: {}.{}", targetSchema, table);
            return Map.of(
                    "table", table,
                    "error", "Table not found",
                    "values", Map.of()
            );
        }

        // Find PK column
        String pkColumn = tableInfo.columns().stream()
                .filter(ColumnInfo::pk)
                .map(ColumnInfo::name)
                .findFirst()
                .orElse(null);

        if (pkColumn == null) {
            // Fallback: use first identity column or first column
            pkColumn = tableInfo.columns().stream()
                    .filter(ColumnInfo::identity)
                    .map(ColumnInfo::name)
                    .findFirst()
                    .orElse(tableInfo.columns().isEmpty() ? "ID" : tableInfo.columns().get(0).name());
        }

        // Resolve display column
        String resolvedDisplayCol = resolveDisplayColumn(tableInfo, displayCol);

        if (!SAFE_IDENTIFIER.matcher(pkColumn).matches() ||
            !SAFE_IDENTIFIER.matcher(resolvedDisplayCol).matches()) {
            throw new IllegalArgumentException("Invalid column name detected");
        }

        // Execute lookup query
        String sql = String.format(
                "SELECT [%s], [%s] FROM [%s].[%s] WHERE [%s] IN (:ids)",
                pkColumn, resolvedDisplayCol, targetSchema, tableInfo.name(), pkColumn
        );

        MapSqlParameterSource params = new MapSqlParameterSource("ids", limitedIds);

        Map<String, String> values = new LinkedHashMap<>();
        try {
            jdbc.query(sql, params, rs -> {
                String id = rs.getString(1);
                String display = rs.getString(2);
                if (id != null) {
                    values.put(id, display != null ? display : id);
                }
            });
        } catch (Exception e) {
            log.error("Lookup query failed for {}.{}: {}", targetSchema, table, e.getMessage());
            return Map.of(
                    "table", tableInfo.name(),
                    "pkColumn", pkColumn,
                    "displayColumn", resolvedDisplayCol,
                    "error", "Query failed: " + e.getMessage(),
                    "values", Map.of()
            );
        }

        log.debug("Lookup {}.{}: {} IDs → {} results (pk={}, display={})",
                targetSchema, tableInfo.name(), limitedIds.size(), values.size(),
                pkColumn, resolvedDisplayCol);

        return Map.of(
                "table", tableInfo.name(),
                "pkColumn", pkColumn,
                "displayColumn", resolvedDisplayCol,
                "values", values
        );
    }

    /**
     * Resolve the best display column for a table.
     * Uses explicit parameter if provided, otherwise tries common name patterns.
     */
    private String resolveDisplayColumn(TableInfo tableInfo, String explicitColumn) {
        if (explicitColumn != null && !explicitColumn.isBlank()) {
            // Verify column exists
            boolean exists = tableInfo.columns().stream()
                    .anyMatch(c -> c.name().equalsIgnoreCase(explicitColumn));
            if (exists) return explicitColumn;
            log.warn("Requested display column '{}' not found in {}, falling back to heuristic",
                    explicitColumn, tableInfo.name());
        }

        Set<String> columnNames = new HashSet<>();
        for (ColumnInfo col : tableInfo.columns()) {
            columnNames.add(col.name().toUpperCase());
        }

        // Try known display column patterns
        for (String candidate : DISPLAY_COLUMN_CANDIDATES) {
            if (columnNames.contains(candidate)) {
                return candidate;
            }
        }

        // Fallback: first non-PK text column (varchar/nvarchar)
        return tableInfo.columns().stream()
                .filter(c -> !c.pk() && isTextType(c.dataType()))
                .map(ColumnInfo::name)
                .findFirst()
                .orElse(tableInfo.columns().stream()
                        .filter(c -> !c.pk())
                        .map(ColumnInfo::name)
                        .findFirst()
                        .orElse(tableInfo.columns().get(0).name()));
    }

    private boolean isTextType(String dataType) {
        String lower = dataType.toLowerCase();
        return lower.contains("char") || lower.contains("text") || lower.contains("string");
    }
}
