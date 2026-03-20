package com.example.report.controller;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Temporary controller for schema discovery — conntest profile only.
 */
@RestController
@RequestMapping("/api/v1/_explore")
@Profile("conntest")
public class SchemaExploreController {

    private final JdbcTemplate jdbc;

    public SchemaExploreController(NamedParameterJdbcTemplate namedJdbc) {
        this.jdbc = namedJdbc.getJdbcTemplate();
        this.jdbc.setQueryTimeout(120);
    }

    @GetMapping("/tables")
    public List<Map<String, Object>> listTables(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "") String search) {
        if (search.isBlank()) {
            return jdbc.queryForList(
                    "SELECT TOP(" + Math.min(limit, 500) + ") " +
                    "s.name AS TABLE_SCHEMA, t.name AS TABLE_NAME, " +
                    "CASE WHEN t.type = 'U' THEN 'TABLE' WHEN t.type = 'V' THEN 'VIEW' ELSE t.type END AS TABLE_TYPE " +
                    "FROM sys.objects t WITH (NOLOCK) " +
                    "JOIN sys.schemas s WITH (NOLOCK) ON t.schema_id = s.schema_id " +
                    "WHERE t.type IN ('U','V') " +
                    "ORDER BY s.name, t.name");
        }
        return jdbc.queryForList(
                "SELECT TOP(" + Math.min(limit, 500) + ") " +
                "s.name AS TABLE_SCHEMA, t.name AS TABLE_NAME, " +
                "CASE WHEN t.type = 'U' THEN 'TABLE' WHEN t.type = 'V' THEN 'VIEW' ELSE t.type END AS TABLE_TYPE " +
                "FROM sys.objects t WITH (NOLOCK) " +
                "JOIN sys.schemas s WITH (NOLOCK) ON t.schema_id = s.schema_id " +
                "WHERE t.type IN ('U','V') AND t.name LIKE ? " +
                "ORDER BY s.name, t.name",
                "%" + search + "%");
    }

    @GetMapping("/columns")
    public List<Map<String, Object>> listColumns(
            @RequestParam String table,
            @RequestParam(defaultValue = "dbo") String schema) {
        return jdbc.queryForList(
                "SELECT c.name AS COLUMN_NAME, tp.name AS DATA_TYPE, " +
                "c.max_length, c.is_nullable " +
                "FROM sys.columns c WITH (NOLOCK) " +
                "JOIN sys.types tp WITH (NOLOCK) ON c.user_type_id = tp.user_type_id " +
                "JOIN sys.objects o WITH (NOLOCK) ON c.object_id = o.object_id " +
                "JOIN sys.schemas s WITH (NOLOCK) ON o.schema_id = s.schema_id " +
                "WHERE o.name = ? AND s.name = ? " +
                "ORDER BY c.column_id",
                table, schema);
    }

    @GetMapping("/sample")
    public List<Map<String, Object>> sampleData(
            @RequestParam String table,
            @RequestParam(defaultValue = "dbo") String schema,
            @RequestParam(defaultValue = "5") int rows) {
        String safeName = table.replaceAll("[^a-zA-Z0-9_]", "");
        String safeSchema = schema.replaceAll("[^a-zA-Z0-9_]", "");
        return jdbc.queryForList(
                "SELECT TOP(" + Math.min(rows, 20) + ") * FROM [" + safeSchema + "].[" + safeName + "] WITH (NOLOCK)");
    }

    @GetMapping("/count")
    public Map<String, Object> countRows(
            @RequestParam String table,
            @RequestParam(defaultValue = "dbo") String schema) {
        String safeName = table.replaceAll("[^a-zA-Z0-9_]", "");
        String safeSchema = schema.replaceAll("[^a-zA-Z0-9_]", "");
        Long count = jdbc.queryForObject(
                "SELECT SUM(p.rows) FROM sys.partitions p WITH (NOLOCK) " +
                "JOIN sys.objects o WITH (NOLOCK) ON p.object_id = o.object_id " +
                "JOIN sys.schemas s WITH (NOLOCK) ON o.schema_id = s.schema_id " +
                "WHERE o.name = ? AND s.name = ? AND p.index_id IN (0,1)",
                Long.class, safeName, safeSchema);
        return Map.of("table", safeName, "schema", safeSchema, "count", count != null ? count : 0);
    }
}
