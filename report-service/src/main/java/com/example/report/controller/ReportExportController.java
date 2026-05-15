package com.example.report.controller;

import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
import com.example.report.dto.ColumnVO;
import com.example.report.dto.ReportExportRequestDto;
import com.example.report.export.CsvStreamingExporter;
import com.example.report.export.ExcelStreamingExporter;
import com.example.report.export.ExportColumn;
import com.example.report.query.QueryEngine;
import com.example.report.query.SqlBuilder;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.PivotValue;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.security.JwtClaimExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportExportController {

    private static final Logger log = LoggerFactory.getLogger(ReportExportController.class);

    private final ReportRegistry registry;
    private final PermissionResolver permissionClient;
    private final ReportAccessEvaluator accessEvaluator;
    private final QueryEngine queryEngine;
    private final NamedParameterJdbcTemplate jdbc;
    private final ReportAuditClient auditClient;
    private final ObjectMapper objectMapper;
    private final CompanyHeaderScopeNarrower companyHeaderNarrower;

    public ReportExportController(ReportRegistry registry,
                                   PermissionResolver permissionClient,
                                   ReportAccessEvaluator accessEvaluator,
                                   QueryEngine queryEngine,
                                   NamedParameterJdbcTemplate jdbc,
                                   ReportAuditClient auditClient,
                                   ObjectMapper objectMapper,
                                   CompanyHeaderScopeNarrower companyHeaderNarrower) {
        this.registry = registry;
        this.permissionClient = permissionClient;
        this.accessEvaluator = accessEvaluator;
        this.queryEngine = queryEngine;
        this.jdbc = jdbc;
        this.auditClient = auditClient;
        this.objectMapper = objectMapper;
        this.companyHeaderNarrower = companyHeaderNarrower;
    }

    @GetMapping("/{key}/export")
    public ResponseEntity<StreamingResponseBody> exportReport(
            @PathVariable String key,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String advancedFilter,
            @RequestHeader(value = CompanyHeaderScopeNarrower.HEADER_NAME, required = false) String companyHeader,
            @AuthenticationPrincipal Jwt jwt) {

        ReportDefinition def = registry.get(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found: " + key));

        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        ReportAccessEvaluator.AccessResult accessResult = accessEvaluator.evaluate(def, authz);
        if (accessResult != ReportAccessEvaluator.AccessResult.ALLOWED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, accessResult.name());
        }
        if (!accessEvaluator.canExport(authz)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "REPORT_EXPORT permission required");
        }
        // Narrow to the picker selection so exported data matches what
        // the user sees on screen (mirrors getData; without it the export
        // would silently include data from every allowed company).
        AuthzMeResponse scopedAuthz = companyHeaderNarrower.narrow(authz, companyHeader);

        Map<String, Object> agGridFilter = parseJson(advancedFilter, new TypeReference<>() {});
        List<Map<String, String>> sortModel = parseJson(sort, new TypeReference<>() {});

        SqlBuilder.BuiltQuery exportQuery = queryEngine.buildExportQuery(def, scopedAuthz, agGridFilter, sortModel);
        List<String> visibleColumns = queryEngine.getVisibleColumns(def, scopedAuthz);

        String userId = jwt != null ? JwtClaimExtractor.extractAuditUsername(jwt) : authz.getUserId();
        auditClient.logReportExport(key, authz.getUserId(), userId, format);

        // Codex 019e0c99 iter-3 §C: export path also propagates degradation
        // warnings as X-Report-Degraded header (dedupe by code).
        var degradationHeaders = com.example.report.query.DegradationHeaders.of(exportQuery.warnings());

        if ("excel".equalsIgnoreCase(format) || "xlsx".equalsIgnoreCase(format)) {
            StreamingResponseBody body = out ->
                    ExcelStreamingExporter.export(jdbc, exportQuery, visibleColumns, def.title(), out);
            return ResponseEntity.ok()
                    .headers(degradationHeaders)
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; filename=\"" + key + ".xlsx\"")
                    .body(body);
        }

        StreamingResponseBody body = out ->
                CsvStreamingExporter.export(jdbc, exportQuery, visibleColumns, out);
        return ResponseEntity.ok()
                .headers(degradationHeaders)
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + key + ".csv\"")
                .body(body);
    }

    /**
     * PR-0.5b (Codex thread 019e2cd7): POST /export. Accepts the AG
     * Grid grid-state snapshot (rowGroupCols + valueCols + pivotCols
     * + pivotMode + filterModel + sortModel) and dispatches to the
     * appropriate {@code SqlBuilder} export-query builder. Flat
     * payloads (no grouping intent) fall through to the same flat
     * builder the legacy GET path uses, so the contract surfaces the
     * three real shapes without code duplication.
     */
    @PostMapping("/{key}/export")
    public ResponseEntity<StreamingResponseBody> exportReportPost(
            @PathVariable String key,
            @RequestBody(required = false) ReportExportRequestDto requestBody,
            @RequestHeader(value = CompanyHeaderScopeNarrower.HEADER_NAME, required = false) String companyHeader,
            @AuthenticationPrincipal Jwt jwt) {

        ReportDefinition def = registry.get(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found: " + key));

        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        ReportAccessEvaluator.AccessResult accessResult = accessEvaluator.evaluate(def, authz);
        if (accessResult != ReportAccessEvaluator.AccessResult.ALLOWED) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, accessResult.name());
        }
        if (!accessEvaluator.canExport(authz)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "REPORT_EXPORT permission required");
        }
        AuthzMeResponse scopedAuthz = companyHeaderNarrower.narrow(authz, companyHeader);

        ReportExportRequestDto safeRequest = requestBody != null ? requestBody
                : new ReportExportRequestDto(null, null, null, null, null, null, null);
        String format = safeRequest.format();
        if (format == null || format.isBlank()) {
            format = "csv";
        }

        Map<String, Object> filterModel = safeRequest.filterModel();
        List<Map<String, String>> sortModel = safeRequest.sortModel();

        // Look up column definition map once — used for friendly headers
        // in grouped/pivot export. ColumnDefinition.headerName() carries
        // the user-facing label registered in the report definition.
        Map<String, ColumnDefinition> columnDefByField = new HashMap<>();
        for (ColumnDefinition cd : def.columns()) {
            columnDefByField.put(cd.field(), cd);
        }

        SqlBuilder.BuiltQuery exportQuery;
        List<ExportColumn> exportColumns;
        try {
            if (safeRequest.requestsPivot()) {
                // PR-0.5b pivot export: single-level row group +
                // single pivot col + non-empty value cols.
                if (safeRequest.rowGroupCols().size() != 1
                        || safeRequest.pivotCols().size() != 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Pivot export currently supports single rowGroup + single pivotCol "
                                    + "(got rowGroup=" + safeRequest.rowGroupCols().size()
                                    + ", pivot=" + safeRequest.pivotCols().size() + ")");
                }
                String groupCol = safeRequest.rowGroupCols().get(0).field();
                String pivotCol = safeRequest.pivotCols().get(0).field();
                List<PivotValue> pivotValues = resolvePivotValues(columnDefByField, pivotCol);
                List<SqlBuilder.GroupedAggregation> aggregations = buildAggregations(
                        safeRequest.valueCols(), columnDefByField);

                SqlBuilder.PivotedBuiltQuery pivotQuery =
                        queryEngine.buildPivotedGroupedExportQuery(
                                def, scopedAuthz, groupCol, pivotCol, pivotValues,
                                aggregations, filterModel, sortModel);
                exportQuery = new SqlBuilder.BuiltQuery(
                        pivotQuery.sql(), pivotQuery.params(), pivotQuery.warnings());
                exportColumns = pivotExportColumns(groupCol, columnDefByField, pivotQuery);
            } else if (safeRequest.requestsGrouping()
                    && safeRequest.rowGroupCols() != null
                    && !safeRequest.rowGroupCols().isEmpty()
                    && safeRequest.valueCols() != null
                    && !safeRequest.valueCols().isEmpty()) {
                // PR-0.5b grouped export: multi-level GROUP BY,
                // leaf-bucket table output.
                List<String> groupColumns = new ArrayList<>();
                for (ColumnVO vo : safeRequest.rowGroupCols()) {
                    groupColumns.add(vo.field());
                }
                List<SqlBuilder.GroupedAggregation> aggregations = buildAggregations(
                        safeRequest.valueCols(), columnDefByField);

                exportQuery = queryEngine.buildGroupedExportQuery(
                        def, scopedAuthz, groupColumns, aggregations,
                        filterModel, sortModel);
                exportColumns = groupedExportColumns(groupColumns, columnDefByField, aggregations);
            } else {
                // Flat export — same shape as the legacy GET path so
                // the request-via-POST contract works uniformly even
                // for non-grouping payloads (frontend dispatches
                // grouping intent → POST, flat → GET; but POST flat
                // stays well-defined for robustness).
                exportQuery = queryEngine.buildExportQuery(
                        def, scopedAuthz, filterModel, sortModel);
                List<String> visibleColumns = queryEngine.getVisibleColumns(def, scopedAuthz);
                exportColumns = flatExportColumns(visibleColumns, columnDefByField);
            }
        } catch (IllegalArgumentException iae) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, iae.getMessage());
        }

        String userId = jwt != null ? JwtClaimExtractor.extractAuditUsername(jwt) : authz.getUserId();
        auditClient.logReportExport(key, authz.getUserId(), userId, format);

        HttpHeaders degradationHeaders =
                com.example.report.query.DegradationHeaders.of(exportQuery.warnings());

        SqlBuilder.BuiltQuery finalQuery = exportQuery;
        List<ExportColumn> finalColumns = exportColumns;

        if ("excel".equalsIgnoreCase(format) || "xlsx".equalsIgnoreCase(format)) {
            StreamingResponseBody body = out ->
                    ExcelStreamingExporter.exportWithColumns(jdbc, finalQuery, finalColumns, def.title(), out);
            return ResponseEntity.ok()
                    .headers(degradationHeaders)
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; filename=\"" + key + ".xlsx\"")
                    .body(body);
        }

        StreamingResponseBody body = out ->
                CsvStreamingExporter.exportWithColumns(jdbc, finalQuery, finalColumns, out);
        return ResponseEntity.ok()
                .headers(degradationHeaders)
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + key + ".csv\"")
                .body(body);
    }

    /**
     * Map AG Grid {@code valueCols} → SqlBuilder
     * {@code GroupedAggregation}. Each entry resolves the aggregation
     * func from (in order) the request's {@code aggFunc}, the column
     * registry's {@code defaultAggFunc}, or "sum" for numeric / "count"
     * for any other type.
     *
     * <p>{@code defaultAggParams} on the registry side carries the
     * {@code weightField} (PR-0.4c) which the request may override
     * via {@code aggParams.weightField}.
     */
    private List<SqlBuilder.GroupedAggregation> buildAggregations(
            List<ColumnVO> valueCols,
            Map<String, ColumnDefinition> columnDefByField) {
        List<SqlBuilder.GroupedAggregation> out = new ArrayList<>();
        if (valueCols == null) return out;
        for (ColumnVO vc : valueCols) {
            String field = vc.field();
            ColumnDefinition cd = columnDefByField.get(field);
            String func = vc.aggFunc();
            if (func == null || func.isBlank()) {
                if (cd != null && cd.defaultAggFunc() != null && !cd.defaultAggFunc().isBlank()) {
                    func = cd.defaultAggFunc();
                } else if (cd != null && "number".equalsIgnoreCase(cd.type())) {
                    func = "sum";
                } else {
                    func = "count";
                }
            }
            func = func.toLowerCase(java.util.Locale.ROOT);
            // Resolve aggParams: request override > registry default.
            Map<String, Object> aggParams = null;
            if (cd != null && cd.defaultAggParams() != null && !cd.defaultAggParams().isEmpty()) {
                aggParams = new LinkedHashMap<>(cd.defaultAggParams());
            }
            out.add(new SqlBuilder.GroupedAggregation(field, func, aggParams));
        }
        return out;
    }

    private List<PivotValue> resolvePivotValues(
            Map<String, ColumnDefinition> columnDefByField, String pivotCol) {
        ColumnDefinition cd = columnDefByField.get(pivotCol);
        if (cd == null || cd.pivotValues() == null || cd.pivotValues().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pivot export requires the pivot column to declare pivotValues in the "
                            + "report registry, got: " + pivotCol);
        }
        return cd.pivotValues();
    }

    /**
     * Build the {@link ExportColumn} list for a flat export — header
     * uses the registry's {@code headerName} when present, otherwise
     * falls back to the raw field.
     */
    private List<ExportColumn> flatExportColumns(
            List<String> visibleColumns,
            Map<String, ColumnDefinition> columnDefByField) {
        List<ExportColumn> out = new ArrayList<>();
        for (String field : visibleColumns) {
            ColumnDefinition cd = columnDefByField.get(field);
            String header = cd != null && cd.headerName() != null && !cd.headerName().isBlank()
                    ? cd.headerName() : field;
            out.add(new ExportColumn(field, header));
        }
        return out;
    }

    /**
     * Build the {@link ExportColumn} list for a grouped (non-pivot)
     * export: every group column + {@code _rowCount} + each
     * aggregation alias.
     */
    private List<ExportColumn> groupedExportColumns(
            List<String> groupColumns,
            Map<String, ColumnDefinition> columnDefByField,
            List<SqlBuilder.GroupedAggregation> aggregations) {
        List<ExportColumn> out = new ArrayList<>();
        for (String groupCol : groupColumns) {
            ColumnDefinition cd = columnDefByField.get(groupCol);
            String header = cd != null && cd.headerName() != null && !cd.headerName().isBlank()
                    ? cd.headerName() : groupCol;
            out.add(new ExportColumn(groupCol, header));
        }
        out.add(new ExportColumn("_rowCount", "#"));
        for (SqlBuilder.GroupedAggregation agg : aggregations) {
            ColumnDefinition cd = columnDefByField.get(agg.field());
            String valueLabel = cd != null && cd.headerName() != null && !cd.headerName().isBlank()
                    ? cd.headerName() : agg.field();
            String header = agg.func().toUpperCase(java.util.Locale.ROOT)
                    + "(" + valueLabel + ")";
            out.add(new ExportColumn(agg.field(), header));
        }
        return out;
    }

    /**
     * Build the {@link ExportColumn} list for a pivot export:
     * {@code groupColumn + _rowCount + (<pivotLabel> / <AGG>(<valueLabel>))*}.
     */
    private List<ExportColumn> pivotExportColumns(
            String groupColumn,
            Map<String, ColumnDefinition> columnDefByField,
            SqlBuilder.PivotedBuiltQuery pivotQuery) {
        List<ExportColumn> out = new ArrayList<>();
        ColumnDefinition groupCd = columnDefByField.get(groupColumn);
        String groupHeader = groupCd != null && groupCd.headerName() != null
                && !groupCd.headerName().isBlank()
                ? groupCd.headerName() : groupColumn;
        out.add(new ExportColumn(groupColumn, groupHeader));
        out.add(new ExportColumn("_rowCount", "#"));
        for (com.example.report.query.PivotResultColumn prc : pivotQuery.pivotResultColumns()) {
            ColumnDefinition valueCd = columnDefByField.get(prc.valueField());
            String valueLabel = valueCd != null && valueCd.headerName() != null
                    && !valueCd.headerName().isBlank()
                    ? valueCd.headerName() : prc.valueField();
            String header = prc.pivotLabel() + " / "
                    + prc.aggFunc().toUpperCase(java.util.Locale.ROOT)
                    + "(" + valueLabel + ")";
            out.add(new ExportColumn(prc.field(), header));
        }
        return out;
    }

    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("Failed to parse JSON parameter: {}", e.getMessage());
            return null;
        }
    }
}
