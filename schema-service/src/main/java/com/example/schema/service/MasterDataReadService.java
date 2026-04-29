package com.example.schema.service;

import com.example.schema.dto.MasterDataItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Codex 019dda1c iter-29: master-data live MSSQL reader. Picks the same
 * MSSQL connection that {@link SchemaExtractService} uses for metadata
 * extraction and runs an allowlist of SELECT queries — one per scope kind
 * the platform supports (companies/projects/branches/departments).
 *
 * <p>Identifier injection guard: schema and table names are NEVER taken
 * from user input. The {@code kind} string maps via a fixed enum-like
 * switch to a hard-coded {@link TableMapping}; dynamic SQL is not built
 * from request payload. Limit is configurable but capped server-side.
 *
 * <p>Failure mode: if the MSSQL connection fails or the table is missing,
 * the service logs a warning and returns an empty list. Callers (the
 * permission-service proxy) translate this into the same empty UX the
 * Postgres path produced.
 */
@Service
public class MasterDataReadService {

    private static final Logger log = LoggerFactory.getLogger(MasterDataReadService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final String schemaName;
    private final int rowLimit;

    public MasterDataReadService(
            NamedParameterJdbcTemplate jdbc,
            @Value("${schema.master-data.schema:${schema.default-schema:workcube_mikrolink}}") String schemaName,
            @Value("${schema.master-data.limit:1000}") int rowLimit) {
        this.jdbc = jdbc;
        this.schemaName = schemaName;
        this.rowLimit = Math.min(Math.max(rowLimit, 1), 5000); // hard cap at 5k regardless of config
    }

    private record TableMapping(String tableName, String idColumn, String nameColumn, String statusColumn) {}

    /**
     * Codex 019dda1c iter-29 SECURITY: schema/table/column identifiers
     * are interpolated into SQL — we never accept them from request input.
     * The mapping here is the entire allowlist; unknown {@code kind} →
     * IllegalArgumentException → 400 in the controller.
     */
    private static final Map<String, TableMapping> KIND_MAP = Map.of(
            "companies",   new TableMapping("our_company",  "comp_id",       "company_name",   "comp_status"),
            "projects",    new TableMapping("pro_projects", "project_id",    "project_name",   "project_status"),
            "branches",    new TableMapping("branch",       "branch_id",     "branch_name",    "branch_status"),
            // Workcube DEPARTMENTS row uses department_detail / department_head as the
            // "human label"; coalesce so neither blank produces an empty cell.
            "departments", new TableMapping("department",   "department_id", "COALESCE(department_detail, department_head)", "department_status")
    );

    public List<MasterDataItemDto> list(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        TableMapping mapping = KIND_MAP.get(normalized);
        if (mapping == null) {
            throw new IllegalArgumentException("Unknown master-data kind: " + kind);
        }

        // MSSQL syntax: TOP (n), bracketed identifiers, ISNULL/COALESCE-equivalent.
        // schemaName + table/column names came from the static allowlist above,
        // not from HTTP input — safe to interpolate.
        String sql = String.format(Locale.ROOT,
                "SELECT TOP (%d) [%s] AS id, %s AS name, COALESCE(%s, 1) AS status "
                        + "FROM [%s].[%s] "
                        + "ORDER BY name, id",
                rowLimit,
                mapping.idColumn(),
                bracketIfPlain(mapping.nameColumn()),
                mapping.statusColumn(),
                schemaName,
                mapping.tableName());

        try {
            return jdbc.query(sql, Map.of(), (rs, rowNum) -> new MasterDataItemDto(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getBoolean("status")
            ));
        } catch (DataAccessException ex) {
            log.warn("MasterData live MSSQL read failed for kind={} schema={}; reason={}",
                    normalized, schemaName,
                    ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * If the column expression is a plain identifier, wrap in MSSQL brackets;
     * if it's a function/expression (already contains parens or commas), pass
     * through as-is so {@code COALESCE(department_detail, department_head)}
     * stays unbracketed.
     */
    private static String bracketIfPlain(String expr) {
        if (expr == null) return null;
        if (expr.contains("(") || expr.contains(",") || expr.contains(" ")) return expr;
        return "[" + expr + "]";
    }
}
