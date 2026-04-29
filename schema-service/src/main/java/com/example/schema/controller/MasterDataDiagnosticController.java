package com.example.schema.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codex 019dda1c iter-30d: one-shot diagnostic endpoint to inspect the
 * DEPARTMENT row distribution before locking down the warehouse-scope
 * filter strategy. User reported "depolarda hala sağlıklı değil adres
 * yazanlar var; filter doğru mu?". This endpoint surfaces:
 *
 * <ul>
 *   <li>Total DEPARTMENT count (unfiltered)</li>
 *   <li>IS_STORE=1 / IS_PRODUCTION=1 / IS_ORGANIZATION=1 distribution</li>
 *   <li>Name-source coverage: how many rows actually have a usable
 *       SETUP_DEPARTMENT_NAME match vs DEPARTMENT_HEAD/DETAIL fallback</li>
 *   <li>Whether DEPARTMENT_ADDRESS (a separate address column) is
 *       populated — explains why DETAIL contains addresses</li>
 *   <li>BRANCH_ID coverage and whether joining BRANCH.BRANCH_NAME would
 *       give a stable warehouse label</li>
 * </ul>
 *
 * <p>Internal endpoint, gated by the same X-Internal-Api-Key header as
 * MasterDataReadController. Will be removed once the warehouse-filter
 * strategy is final.
 */
@RestController
@RequestMapping("/api/v1/schema/master-data-diagnostic")
public class MasterDataDiagnosticController {

    private static final Logger log = LoggerFactory.getLogger(MasterDataDiagnosticController.class);
    private static final String INTERNAL_HEADER = "X-Internal-Api-Key";

    private final NamedParameterJdbcTemplate jdbc;
    private final String schemaName;
    private final String expectedKey;

    public MasterDataDiagnosticController(
            NamedParameterJdbcTemplate jdbc,
            @Value("${schema.master-data.schema:${schema.default-schema:workcube_mikrolink}}") String schemaName,
            @Value("${schema.master-data.internal-api-key:}") String expectedKey) {
        this.jdbc = jdbc;
        this.schemaName = schemaName;
        this.expectedKey = expectedKey;
    }

    @GetMapping("/departments")
    public ResponseEntity<Map<String, Object>> deptDiagnostic(
            @RequestHeader(value = INTERNAL_HEADER, required = false) String providedKey) {

        if (expectedKey != null && !expectedKey.isBlank()
                && (providedKey == null || !expectedKey.equals(providedKey))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid internal API key");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Total + flag distribution
            String distSql = String.format("""
                    SELECT
                        COUNT(*) AS total_rows,
                        SUM(CASE WHEN [IS_STORE] = 1 THEN 1 ELSE 0 END) AS is_store_1,
                        SUM(CASE WHEN [IS_STORE] = 0 OR [IS_STORE] IS NULL THEN 1 ELSE 0 END) AS is_store_0_or_null,
                        SUM(CASE WHEN [IS_PRODUCTION] = 1 THEN 1 ELSE 0 END) AS is_production_1,
                        SUM(CASE WHEN [IS_ORGANIZATION] = 1 THEN 1 ELSE 0 END) AS is_organization_1,
                        SUM(CASE WHEN [IS_STORE] = 1 AND [IS_PRODUCTION] = 1 THEN 1 ELSE 0 END) AS store_and_production,
                        SUM(CASE WHEN [IS_STORE] = 1 AND [IS_ORGANIZATION] = 1 THEN 1 ELSE 0 END) AS store_and_organization,
                        SUM(CASE WHEN [IS_STORE] = 1 AND [IS_PRODUCTION] IS NULL AND [IS_ORGANIZATION] IS NULL THEN 1 ELSE 0 END) AS pure_store
                    FROM [%s].[DEPARTMENT]
                    """, schemaName);
            Map<String, Object> dist = jdbc.queryForMap(distSql, Map.of());
            result.put("flags", dist);

            // Name source coverage for IS_STORE=1
            String coverageSql = String.format("""
                    SELECT
                        COUNT(*) AS store_total,
                        SUM(CASE WHEN dn.[DEPARTMENT_NAME] IS NOT NULL AND LEN(LTRIM(RTRIM(dn.[DEPARTMENT_NAME]))) > 0 THEN 1 ELSE 0 END) AS lookup_match,
                        SUM(CASE WHEN d.[DEPARTMENT_DETAIL] IS NOT NULL AND LEN(LTRIM(RTRIM(d.[DEPARTMENT_DETAIL]))) > 0 THEN 1 ELSE 0 END) AS detail_filled,
                        SUM(CASE WHEN d.[DEPARTMENT_HEAD] IS NOT NULL AND LEN(LTRIM(RTRIM(d.[DEPARTMENT_HEAD]))) > 0 THEN 1 ELSE 0 END) AS head_filled,
                        SUM(CASE WHEN d.[DEPARTMENT_ADDRESS] IS NOT NULL AND LEN(LTRIM(RTRIM(d.[DEPARTMENT_ADDRESS]))) > 0 THEN 1 ELSE 0 END) AS address_filled,
                        SUM(CASE WHEN d.[BRANCH_ID] IS NOT NULL THEN 1 ELSE 0 END) AS branch_id_filled,
                        SUM(CASE WHEN d.[SPECIAL_CODE] IS NOT NULL AND LEN(LTRIM(RTRIM(d.[SPECIAL_CODE]))) > 0 THEN 1 ELSE 0 END) AS special_code_filled
                    FROM [%1$s].[DEPARTMENT] d
                    LEFT JOIN [%1$s].[SETUP_DEPARTMENT_NAME] dn ON dn.[DEPARTMENT_NAME_ID] = d.[_DEPARTMENT_NAME_ID]
                    WHERE d.[IS_STORE] = 1
                    """, schemaName);
            Map<String, Object> coverage = jdbc.queryForMap(coverageSql, Map.of());
            result.put("is_store_1_name_coverage", coverage);

            // Sample of IS_STORE=1 rows with all candidate name sources visible
            String sampleSql = String.format("""
                    SELECT TOP (10)
                        d.[DEPARTMENT_ID]                                    AS id,
                        d.[BRANCH_ID]                                        AS branch_id,
                        b.[BRANCH_NAME]                                      AS branch_name,
                        dn.[DEPARTMENT_NAME]                                 AS lookup_name,
                        d.[DEPARTMENT_HEAD]                                  AS dept_head,
                        d.[DEPARTMENT_DETAIL]                                AS dept_detail,
                        d.[DEPARTMENT_ADDRESS]                               AS dept_address,
                        d.[SPECIAL_CODE]                                     AS special_code,
                        d.[DEPARTMENT_TYPE]                                  AS dept_type,
                        d.[DEPARTMENT_CAT]                                   AS dept_cat
                    FROM [%1$s].[DEPARTMENT] d
                    LEFT JOIN [%1$s].[SETUP_DEPARTMENT_NAME] dn ON dn.[DEPARTMENT_NAME_ID] = d.[_DEPARTMENT_NAME_ID]
                    LEFT JOIN [%1$s].[BRANCH] b ON b.[BRANCH_ID] = d.[BRANCH_ID]
                    WHERE d.[IS_STORE] = 1
                    ORDER BY d.[DEPARTMENT_ID]
                    """, schemaName);
            List<Map<String, Object>> samples = jdbc.queryForList(sampleSql, Map.of());
            result.put("samples_is_store_1", samples);

            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            log.warn("Department diagnostic failed: {}", ex.toString());
            result.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }
}
