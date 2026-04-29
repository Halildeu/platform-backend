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

    /**
     * Codex 019dda1c iter-30: full SQL templates per kind. iter-29 pre-image
     * tried to mechanically compose tableName + idCol + nameCol; that doesn't
     * fit the real schema once you need a JOIN (DEPARTMENT name lives in
     * SETUP_DEPARTMENT_NAME) or a code column on top of name. The template
     * uses {@code %d} for the configurable row limit; placeholders here come
     * from a static allowlist, never from request input.
     *
     * <p>Each query:
     * <ul>
     *   <li>aliases columns to {@code id, code, name, status} (matches DTO)</li>
     *   <li>filters out rows whose name is NULL or empty (no blank checkboxes)</li>
     *   <li>orders by name then id for stable UI</li>
     * </ul>
     */
    private record TableMapping(String sqlTemplate) {}

    private static final Map<String, TableMapping> KIND_MAP = Map.of(
            "companies",   new TableMapping("""
                    SELECT TOP (%1$d)
                        c.[COMP_ID]            AS id,
                        c.[COMPANY_SHORT_CODE] AS code,
                        c.[COMPANY_NAME]       AS name,
                        COALESCE(c.[COMP_STATUS], 1) AS status
                    FROM [%2$s].[OUR_COMPANY] c
                    WHERE c.[COMPANY_NAME] IS NOT NULL AND LEN(LTRIM(RTRIM(c.[COMPANY_NAME]))) > 0
                    ORDER BY c.[COMPANY_NAME], c.[COMP_ID]
                    """),
            "projects",    new TableMapping("""
                    SELECT TOP (%1$d)
                        p.[PROJECT_ID]        AS id,
                        p.[PROJECT_NUMBER]    AS code,
                        p.[PROJECT_HEAD]      AS name,
                        COALESCE(p.[PROJECT_STATUS], 1) AS status
                    FROM [%2$s].[PRO_PROJECTS] p
                    WHERE p.[PROJECT_HEAD] IS NOT NULL AND LEN(LTRIM(RTRIM(p.[PROJECT_HEAD]))) > 0
                    ORDER BY p.[PROJECT_HEAD], p.[PROJECT_ID]
                    """),
            "branches",    new TableMapping("""
                    SELECT TOP (%1$d)
                        b.[BRANCH_ID]   AS id,
                        NULL            AS code,
                        b.[BRANCH_NAME] AS name,
                        COALESCE(b.[BRANCH_STATUS], 1) AS status
                    FROM [%2$s].[BRANCH] b
                    WHERE b.[BRANCH_NAME] IS NOT NULL AND LEN(LTRIM(RTRIM(b.[BRANCH_NAME]))) > 0
                    ORDER BY b.[BRANCH_NAME], b.[BRANCH_ID]
                    """),
            // DEPARTMENT.DEPARTMENT_DETAIL and DEPARTMENT_HEAD are blank for most
            // rows in the live data. The actual human-readable name lives in
            // SETUP_DEPARTMENT_NAME, joined via DEPARTMENT._DEPARTMENT_NAME_ID.
            // SPECIAL_CODE is used as a code field (most departments have one).
            "departments", new TableMapping("""
                    SELECT TOP (%1$d)
                        d.[DEPARTMENT_ID]      AS id,
                        d.[SPECIAL_CODE]       AS code,
                        dn.[DEPARTMENT_NAME]   AS name,
                        COALESCE(d.[DEPARTMENT_STATUS], 1) AS status
                    FROM [%2$s].[DEPARTMENT] d
                    LEFT JOIN [%2$s].[SETUP_DEPARTMENT_NAME] dn
                        ON dn.[DEPARTMENT_NAME_ID] = d.[_DEPARTMENT_NAME_ID]
                    WHERE dn.[DEPARTMENT_NAME] IS NOT NULL AND LEN(LTRIM(RTRIM(dn.[DEPARTMENT_NAME]))) > 0
                    ORDER BY dn.[DEPARTMENT_NAME], d.[DEPARTMENT_ID]
                    """)
    );

    public List<MasterDataItemDto> list(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        TableMapping mapping = KIND_MAP.get(normalized);
        if (mapping == null) {
            throw new IllegalArgumentException("Unknown master-data kind: " + kind);
        }

        // Schema name + limit interpolated from server-side allowlist/config —
        // never from request input. SQL injection guard relies on KIND_MAP
        // being the closed allowlist (no kind → 400 above).
        String sql = String.format(Locale.ROOT, mapping.sqlTemplate(), rowLimit, schemaName);

        try {
            return jdbc.query(sql, Map.of(), (rs, rowNum) -> new MasterDataItemDto(
                    rs.getLong("id"),
                    rs.getString("code"),
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
}
