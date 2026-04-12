package com.example.report.controller;

import com.example.report.access.ColumnFilter;
import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import com.example.report.dto.CategoryDto;
import com.example.report.dto.PagedResultDto;
import com.example.report.dto.ReportListItemDto;
import com.example.report.dto.ReportMetadataDto;
import com.example.report.query.QueryEngine;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.repository.CustomReportRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportRegistry registry;
    private final CustomReportRepository customReportRepository;
    private final PermissionResolver permissionClient;
    private final ReportAccessEvaluator accessEvaluator;
    private final ColumnFilter columnFilter;
    private final QueryEngine queryEngine;
    private final ReportAuditClient auditClient;
    private final ObjectMapper objectMapper;

    public ReportController(ReportRegistry registry,
                            CustomReportRepository customReportRepository,
                            PermissionResolver permissionClient,
                            ReportAccessEvaluator accessEvaluator,
                            ColumnFilter columnFilter,
                            QueryEngine queryEngine,
                            ReportAuditClient auditClient,
                            ObjectMapper objectMapper) {
        this.registry = registry;
        this.customReportRepository = customReportRepository;
        this.permissionClient = permissionClient;
        this.accessEvaluator = accessEvaluator;
        this.columnFilter = columnFilter;
        this.queryEngine = queryEngine;
        this.auditClient = auditClient;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<ReportListItemDto>> listReports(@AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);

        // Static reports from JSON registry
        List<ReportListItemDto> staticReports = registry.getAll().stream()
                .filter(def -> accessEvaluator.evaluate(def, authz) == ReportAccessEvaluator.AccessResult.ALLOWED)
                .map(def -> new ReportListItemDto(def.key(), def.title(), def.description(), def.category(),
                        def.access() != null ? def.access().reportGroup() : null))
                .toList();

        // Custom reports from PostgreSQL — filtered by access_config reportGroup (CNS-006 R17)
        List<ReportListItemDto> customReports = List.of();
        try {
            customReports = customReportRepository.findAll().stream()
                    .filter(row -> evaluateCustomReportAccess(row, authz))
                    .map(row -> new ReportListItemDto(
                            (String) row.get("key"),
                            (String) row.get("title"),
                            (String) row.get("description"),
                            (String) row.get("category"),
                            extractReportGroup(row)
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to load custom reports from PostgreSQL: {}", e.getMessage());
        }

        // Merge: static + custom (static wins on key conflict)
        var staticKeys = staticReports.stream().map(ReportListItemDto::key).collect(java.util.stream.Collectors.toSet());
        List<ReportListItemDto> merged = new ArrayList<>(staticReports);
        customReports.stream()
                .filter(r -> !staticKeys.contains(r.key()))
                .forEach(merged::add);

        return ResponseEntity.ok(merged);
    }

    /* ---- Custom Report CRUD (CNS-006 R16: OpenFGA permission enforced) ---- */

    @PostMapping
    public ResponseEntity<Map<String, Object>> createCustomReport(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        requireReportManage(authz, jwt, "CREATE");

        String username = jwt != null ? jwt.getClaimAsString("preferred_username") : "system";
        body.put("createdBy", username);
        Map<String, Object> saved = customReportRepository.save(body);
        auditClient.logReportAccess("custom:" + saved.get("key"), authz.getUserId(), extractEmail(jwt));
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> updateCustomReport(
            @PathVariable String key,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        requireReportManageOrOwner(authz, jwt, key, "UPDATE");

        String username = jwt != null ? jwt.getClaimAsString("preferred_username") : "system";
        body.put("createdBy", username);
        Map<String, Object> updated = customReportRepository.update(key, body);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> deleteCustomReport(
            @PathVariable String key,
            @AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        requireReportManageOrOwner(authz, jwt, key, "DELETE");

        boolean deleted = customReportRepository.softDelete(key);
        if (deleted) {
            auditClient.logReportAccessDenied("custom:" + key, authz.getUserId(), extractEmail(jwt), "SOFT_DELETED");
        }
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/{key}/history")
    public ResponseEntity<List<Map<String, Object>>> getReportHistory(
            @PathVariable String key,
            @AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        requireReportView(authz, jwt);

        List<Map<String, Object>> history = customReportRepository.getVersionHistory(key);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryDto>> listCategories(@AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);

        Map<String, Long> categoryCounts = registry.getAll().stream()
                .filter(def -> accessEvaluator.evaluate(def, authz) == ReportAccessEvaluator.AccessResult.ALLOWED)
                .collect(java.util.stream.Collectors.groupingBy(
                        ReportDefinition::category,
                        java.util.stream.Collectors.counting()));

        List<CategoryDto> categories = categoryCounts.entrySet().stream()
                .map(e -> new CategoryDto(e.getKey(), e.getValue()))
                .sorted(java.util.Comparator.comparing(CategoryDto::name))
                .toList();

        return ResponseEntity.ok(categories);
    }

    @GetMapping("/{key}/metadata")
    public ResponseEntity<ReportMetadataDto> getMetadata(@PathVariable String key,
                                                          @AuthenticationPrincipal Jwt jwt) {
        ReportDefinition def = findReportOrThrow(key);
        AuthzMeResponse authz = resolveAndCheckAccess(def, jwt);

        List<ColumnDefinition> visibleCols = columnFilter.getVisibleColumnDefinitions(def, authz);

        return ResponseEntity.ok(new ReportMetadataDto(
                def.key(), def.title(), def.description(), def.category(),
                visibleCols, def.defaultSort(), def.defaultSortDirection()));
    }

    @GetMapping("/{key}/data")
    public ResponseEntity<PagedResultDto<Map<String, Object>>> getData(
            @PathVariable String key,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String advancedFilter,
            @AuthenticationPrincipal Jwt jwt) {

        ReportDefinition def = findReportOrThrow(key);
        AuthzMeResponse authz = resolveAndCheckAccess(def, jwt);

        Map<String, Object> agGridFilter = parseJson(advancedFilter, new TypeReference<>() {});
        List<Map<String, String>> sortModel = parseJson(sort, new TypeReference<>() {});

        pageSize = Math.min(Math.max(pageSize, 1), 500);

        QueryEngine.PagedData result = queryEngine.executeQuery(def, authz, agGridFilter, sortModel, page, pageSize);

        auditClient.logReportAccess(key, authz.getUserId(), extractEmail(jwt));

        return ResponseEntity.ok(new PagedResultDto<>(result.items(), result.total(), result.page(), result.pageSize()));
    }

    private ReportDefinition findReportOrThrow(String key) {
        return registry.get(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found: " + key));
    }

    private AuthzMeResponse resolveAndCheckAccess(ReportDefinition def, Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        ReportAccessEvaluator.AccessResult result = accessEvaluator.evaluate(def, authz);

        if (result != ReportAccessEvaluator.AccessResult.ALLOWED) {
            auditClient.logReportAccessDenied(def.key(),
                    authz != null ? authz.getUserId() : "unknown",
                    extractEmail(jwt),
                    result.name());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, result.name());
        }
        return authz;
    }

    /* ---- Authorization helpers (CNS-006 R16/R17) ---- */

    private void requireReportView(AuthzMeResponse authz, Jwt jwt) {
        if (authz == null || (!authz.isSuperAdmin() && !authz.hasPermission("REPORT_VIEW"))) {
            auditClient.logReportAccessDenied("custom:*",
                    authz != null ? authz.getUserId() : "unknown",
                    extractEmail(jwt), "DENIED_NO_REPORT_VIEW");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DENIED_NO_REPORT_VIEW");
        }
    }

    private void requireReportManage(AuthzMeResponse authz, Jwt jwt, String action) {
        if (authz == null || (!authz.isSuperAdmin() && !authz.hasPermission("REPORT_MANAGE"))) {
            auditClient.logReportAccessDenied("custom:" + action,
                    authz != null ? authz.getUserId() : "unknown",
                    extractEmail(jwt), "DENIED_NO_REPORT_MANAGE");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DENIED_NO_REPORT_MANAGE");
        }
    }

    private void requireReportManageOrOwner(AuthzMeResponse authz, Jwt jwt, String key, String action) {
        if (authz != null && authz.isSuperAdmin()) {
            return;
        }
        if (authz != null && authz.hasPermission("REPORT_MANAGE")) {
            return;
        }
        // Fallback: owner can modify their own reports
        String username = jwt != null ? jwt.getClaimAsString("preferred_username") : null;
        if (username != null) {
            Optional<Map<String, Object>> existing = customReportRepository.findByKey(key);
            if (existing.isPresent() && username.equals(existing.get().get("createdBy"))) {
                return;
            }
        }
        auditClient.logReportAccessDenied("custom:" + key + ":" + action,
                authz != null ? authz.getUserId() : "unknown",
                extractEmail(jwt), "DENIED_NOT_OWNER_OR_MANAGE");
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DENIED_NOT_OWNER_OR_MANAGE");
    }

    /**
     * Evaluate access to a custom report based on its access_config.reportGroup field.
     * CNS-006 R17: deny-default when reportGroup is set and user doesn't have ALLOW grant.
     */
    @SuppressWarnings("unchecked")
    private boolean evaluateCustomReportAccess(Map<String, Object> row, AuthzMeResponse authz) {
        if (authz.isSuperAdmin()) {
            return true;
        }
        if (!authz.hasPermission("REPORT_VIEW")) {
            return false;
        }
        Object accessConfigObj = row.get("accessConfig");
        if (accessConfigObj instanceof Map<?, ?> accessConfig) {
            Object reportGroup = accessConfig.get("reportGroup");
            if (reportGroup instanceof String group && !group.isBlank()) {
                return authz.canViewReport(group);
            }
        }
        // No reportGroup in access_config → allow if user has REPORT_VIEW (backwards compat)
        return true;
    }

    @SuppressWarnings("unchecked")
    private String extractReportGroup(Map<String, Object> row) {
        Object accessConfigObj = row.get("accessConfig");
        if (accessConfigObj instanceof Map<?, ?> accessConfig) {
            Object group = accessConfig.get("reportGroup");
            return group instanceof String s ? s : null;
        }
        return null;
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

    private String extractEmail(Jwt jwt) {
        if (jwt == null) return "anonymous";
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getSubject();
    }
}
