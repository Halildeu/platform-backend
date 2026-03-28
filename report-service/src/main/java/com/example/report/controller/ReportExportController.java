package com.example.report.controller;

import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import com.example.report.export.CsvStreamingExporter;
import com.example.report.export.ExcelStreamingExporter;
import com.example.report.query.QueryEngine;
import com.example.report.query.SqlBuilder;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    public ReportExportController(ReportRegistry registry,
                                   PermissionResolver permissionClient,
                                   ReportAccessEvaluator accessEvaluator,
                                   QueryEngine queryEngine,
                                   NamedParameterJdbcTemplate jdbc,
                                   ReportAuditClient auditClient,
                                   ObjectMapper objectMapper) {
        this.registry = registry;
        this.permissionClient = permissionClient;
        this.accessEvaluator = accessEvaluator;
        this.queryEngine = queryEngine;
        this.jdbc = jdbc;
        this.auditClient = auditClient;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{key}/export")
    public ResponseEntity<StreamingResponseBody> exportReport(
            @PathVariable String key,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String advancedFilter,
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

        Map<String, Object> agGridFilter = parseJson(advancedFilter, new TypeReference<>() {});
        List<Map<String, String>> sortModel = parseJson(sort, new TypeReference<>() {});

        SqlBuilder.BuiltQuery exportQuery = queryEngine.buildExportQuery(def, authz, agGridFilter, sortModel);
        List<String> visibleColumns = queryEngine.getVisibleColumns(def, authz);

        String email = jwt != null ? jwt.getClaimAsString("email") : null;
        String userId = jwt != null ? (email != null ? email : jwt.getSubject()) : authz.getUserId();
        auditClient.logReportExport(key, authz.getUserId(), userId, format);

        if ("excel".equalsIgnoreCase(format) || "xlsx".equalsIgnoreCase(format)) {
            StreamingResponseBody body = out ->
                    ExcelStreamingExporter.export(jdbc, exportQuery, visibleColumns, def.title(), out);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; filename=\"" + key + ".xlsx\"")
                    .body(body);
        }

        StreamingResponseBody body = out ->
                CsvStreamingExporter.export(jdbc, exportQuery, visibleColumns, out);
        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + key + ".csv\"")
                .body(body);
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
