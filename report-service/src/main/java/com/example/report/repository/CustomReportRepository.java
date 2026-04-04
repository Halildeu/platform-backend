package com.example.report.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class CustomReportRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CustomReportRepository(@Qualifier("pgJdbc") NamedParameterJdbcTemplate jdbc,
                                   ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> findAll() {
        return jdbc.query(
                "SELECT * FROM custom_reports WHERE NOT deleted ORDER BY created_at DESC",
                new MapSqlParameterSource(),
                this::mapRow
        );
    }

    public Optional<Map<String, Object>> findByKey(String key) {
        List<Map<String, Object>> results = jdbc.query(
                "SELECT * FROM custom_reports WHERE key = :key AND NOT deleted",
                new MapSqlParameterSource("key", key),
                this::mapRow
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Map<String, Object> save(Map<String, Object> report) {
        String sql = """
                INSERT INTO custom_reports (key, title, description, category, source_schema,
                    source_table, columns, default_sort, default_sort_direction, access_config,
                    tags, created_by)
                VALUES (:key, :title, :description, :category, :sourceSchema,
                    :sourceTable, :columns::jsonb, :defaultSort, :defaultSortDirection,
                    :accessConfig::jsonb, :tags::text[], :createdBy)
                RETURNING *
                """;
        MapSqlParameterSource params = buildParams(report);
        List<Map<String, Object>> results = jdbc.query(sql, params, this::mapRow);
        return results.isEmpty() ? report : results.get(0);
    }

    public Map<String, Object> update(String key, Map<String, Object> report) {
        // Save version before update
        saveVersion(key);

        String sql = """
                UPDATE custom_reports SET
                    title = :title, description = :description, category = :category,
                    source_schema = :sourceSchema, source_table = :sourceTable,
                    columns = :columns::jsonb, default_sort = :defaultSort,
                    default_sort_direction = :defaultSortDirection,
                    access_config = :accessConfig::jsonb, tags = :tags::text[],
                    version = version + 1, updated_at = NOW()
                WHERE key = :key AND NOT deleted
                RETURNING *
                """;
        MapSqlParameterSource params = buildParams(report);
        params.addValue("key", key);
        List<Map<String, Object>> results = jdbc.query(sql, params, this::mapRow);
        return results.isEmpty() ? report : results.get(0);
    }

    public boolean softDelete(String key) {
        int rows = jdbc.update(
                "UPDATE custom_reports SET deleted = true, updated_at = NOW() WHERE key = :key AND NOT deleted",
                new MapSqlParameterSource("key", key)
        );
        return rows > 0;
    }

    public List<Map<String, Object>> getVersionHistory(String key) {
        return jdbc.query("""
                SELECT v.*, r.key AS report_key, r.title AS report_title
                FROM custom_report_versions v
                JOIN custom_reports r ON r.id = v.report_id
                WHERE r.key = :key
                ORDER BY v.version DESC
                """,
                new MapSqlParameterSource("key", key),
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("version", rs.getInt("version"));
                    row.put("sourceTable", rs.getString("source_table"));
                    row.put("columns", parseJson(rs.getString("columns")));
                    row.put("changedBy", rs.getString("changed_by"));
                    row.put("changedAt", rs.getObject("changed_at", OffsetDateTime.class));
                    return row;
                }
        );
    }

    private void saveVersion(String key) {
        jdbc.update("""
                INSERT INTO custom_report_versions (report_id, version, columns, source_table, changed_by)
                SELECT id, version, columns, source_table, created_by
                FROM custom_reports WHERE key = :key AND NOT deleted
                """,
                new MapSqlParameterSource("key", key)
        );
    }

    private MapSqlParameterSource buildParams(Map<String, Object> report) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("key", report.get("key"));
        params.addValue("title", report.get("title"));
        params.addValue("description", report.get("description"));
        params.addValue("category", report.getOrDefault("category", "Özel"));
        params.addValue("sourceSchema", report.get("sourceSchema"));
        params.addValue("sourceTable", report.get("sourceTable"));
        params.addValue("columns", toJson(report.get("columns")));
        params.addValue("defaultSort", report.get("defaultSort"));
        params.addValue("defaultSortDirection", report.getOrDefault("defaultSortDirection", "asc"));
        params.addValue("accessConfig", toJson(report.get("accessConfig")));
        params.addValue("createdBy", report.getOrDefault("createdBy", "system"));

        Object tags = report.get("tags");
        if (tags instanceof List<?> list) {
            params.addValue("tags", "{" + String.join(",", list.stream().map(Object::toString).toList()) + "}");
        } else {
            params.addValue("tags", "{}");
        }
        return params;
    }

    private Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("key", rs.getString("key"));
        row.put("title", rs.getString("title"));
        row.put("description", rs.getString("description"));
        row.put("category", rs.getString("category"));
        row.put("sourceSchema", rs.getString("source_schema"));
        row.put("sourceTable", rs.getString("source_table"));
        row.put("columns", parseJson(rs.getString("columns")));
        row.put("defaultSort", rs.getString("default_sort"));
        row.put("defaultSortDirection", rs.getString("default_sort_direction"));
        row.put("accessConfig", parseJson(rs.getString("access_config")));
        row.put("version", rs.getInt("version"));
        row.put("createdBy", rs.getString("created_by"));
        row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));
        row.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class));
        row.put("source", "custom");

        Array tagsArr = rs.getArray("tags");
        row.put("tags", tagsArr != null ? Arrays.asList((String[]) tagsArr.getArray()) : List.of());
        return row;
    }

    private String toJson(Object obj) {
        if (obj == null) return "{}";
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
