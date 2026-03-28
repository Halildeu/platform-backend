package com.example.report.controller;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import com.example.report.dto.*;
import com.example.report.query.DashboardQueryEngine;
import com.example.report.registry.DashboardDefinition;
import com.example.report.registry.DashboardRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/dashboards")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);
    private static final Set<String> VALID_TIME_RANGES = Set.of("7d", "30d", "90d", "180d", "1y", "ytd");

    private final DashboardRegistry registry;
    private final PermissionResolver permissionResolver;
    private final DashboardQueryEngine queryEngine;

    public DashboardController(DashboardRegistry registry,
                                PermissionResolver permissionResolver,
                                DashboardQueryEngine queryEngine) {
        this.registry = registry;
        this.permissionResolver = permissionResolver;
        this.queryEngine = queryEngine;
    }

    @GetMapping
    public ResponseEntity<List<DashboardListItemDto>> listDashboards(@AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionResolver.getAuthzMe(jwt);

        List<DashboardListItemDto> dashboards = registry.getAll().stream()
                .filter(def -> hasAccess(def, authz))
                .map(def -> new DashboardListItemDto(
                        def.key(), def.title(), def.description(), def.category(),
                        def.icon(), def.timeRanges(), def.defaultTimeRange(),
                        def.kpis().size(), def.charts().size()))
                .toList();

        return ResponseEntity.ok(dashboards);
    }

    @GetMapping("/{key}")
    public ResponseEntity<DashboardMetadataDto> getMetadata(@PathVariable String key,
                                                              @AuthenticationPrincipal Jwt jwt) {
        DashboardDefinition def = findOrThrow(key);
        checkAccess(def, jwt);

        List<DashboardMetadataDto.KpiMetaDto> kpiMetas = def.kpis().stream()
                .map(k -> new DashboardMetadataDto.KpiMetaDto(k.id(), k.title(), k.format()))
                .toList();

        List<DashboardMetadataDto.ChartMetaDto> chartMetas = def.charts().stream()
                .map(c -> new DashboardMetadataDto.ChartMetaDto(c.id(), c.title(), c.chartType(), c.size()))
                .toList();

        return ResponseEntity.ok(new DashboardMetadataDto(
                def.key(), def.title(), def.description(), def.category(),
                def.icon(), def.timeRanges(), def.defaultTimeRange(),
                def.layout(), kpiMetas, chartMetas));
    }

    @GetMapping("/{key}/kpis")
    public ResponseEntity<List<KpiResultDto>> getKpis(@PathVariable String key,
                                                        @RequestParam(defaultValue = "90d") String timeRange,
                                                        @AuthenticationPrincipal Jwt jwt) {
        DashboardDefinition def = findOrThrow(key);
        AuthzMeResponse authz = checkAccess(def, jwt);
        validateTimeRange(timeRange, def);

        List<KpiResultDto> kpis = queryEngine.executeKpis(def, authz, timeRange);
        return ResponseEntity.ok(kpis);
    }

    @GetMapping("/{key}/charts")
    public ResponseEntity<List<ChartResultDto>> getCharts(@PathVariable String key,
                                                            @RequestParam(defaultValue = "90d") String timeRange,
                                                            @AuthenticationPrincipal Jwt jwt) {
        DashboardDefinition def = findOrThrow(key);
        AuthzMeResponse authz = checkAccess(def, jwt);
        validateTimeRange(timeRange, def);

        List<ChartResultDto> charts = queryEngine.executeCharts(def, authz, timeRange);
        return ResponseEntity.ok(charts);
    }

    private DashboardDefinition findOrThrow(String key) {
        return registry.get(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dashboard not found: " + key));
    }

    private AuthzMeResponse checkAccess(DashboardDefinition def, Jwt jwt) {
        AuthzMeResponse authz = permissionResolver.getAuthzMe(jwt);
        if (!hasAccess(def, authz)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to dashboard: " + def.key());
        }
        return authz;
    }

    private boolean hasAccess(DashboardDefinition def, AuthzMeResponse authz) {
        if (authz == null) return false;
        if (authz.isSuperAdmin()) return true;
        if (!authz.hasPermission("REPORT_VIEW")) return false;
        if (def.access() != null && def.access().permission() != null
                && !def.access().permission().isBlank()) {
            return authz.hasPermission(def.access().permission());
        }
        return true;
    }

    private void validateTimeRange(String timeRange, DashboardDefinition def) {
        if (!VALID_TIME_RANGES.contains(timeRange) && !def.timeRanges().contains(timeRange)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid time range: " + timeRange + ". Valid: " + def.timeRanges());
        }
    }
}
