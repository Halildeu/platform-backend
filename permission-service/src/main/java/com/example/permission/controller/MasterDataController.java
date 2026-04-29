package com.example.permission.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.permission.dataaccess.MasterDataItem;
import com.example.permission.dataaccess.MasterDataService;
import com.example.permission.dataaccess.SchemaMasterDataClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Master data scope picker endpoints — workcube_mikrolink scope tablosu
 * dropdown listeleri.
 *
 * <p>Faz 21.3 follow-up (2026-04-29): Frontend ScopeAssignModal text input →
 * dropdown geçişi için backend listesi. {@link MasterDataService} reports_db
 * secondary datasource'tan direct SQL read.
 *
 * <p>data_access.scope CHECK constraint mapping:
 * <ul>
 *   <li>{@code GET /api/v1/master-data/companies}    → scope_kind="company"</li>
 *   <li>{@code GET /api/v1/master-data/projects}     → scope_kind="project"</li>
 *   <li>{@code GET /api/v1/master-data/branches}     → scope_kind="branch"</li>
 *   <li>{@code GET /api/v1/master-data/departments}  → scope_kind="depot"</li>
 * </ul>
 *
 * <p>Authorization: {@code @RequireModule("ACCESS", "can_view")} —
 * RequireModuleInterceptor superAdmin bypass'ı (PR #20) sayesinde
 * {@code organization:default#admin} tuple sahibi org admin'ler doğrudan
 * geçer; module-specific tuple gerekmez.
 */
@RestController
@RequestMapping("/api/v1/master-data")
public class MasterDataController {

    private final MasterDataService masterDataService;
    private final SchemaMasterDataClient schemaClient;

    @Autowired(required = false)
    public MasterDataController(
            @Nullable MasterDataService masterDataService,
            SchemaMasterDataClient schemaClient) {
        this.masterDataService = masterDataService;
        this.schemaClient = schemaClient;
    }

    /**
     * Codex 019dda1c iter-29: live MSSQL via schema-service primary +
     * legacy reports_db (Postgres mirror) fallback. The schema-service
     * client returns empty when not configured ({@code SCHEMA_INTERNAL_API_KEY}
     * unset) or when the live call fails — both cases short-circuit to the
     * mirror, which today is empty too but preserved for the day ETL fills it.
     */
    private List<MasterDataItem> resolve(String kind, Supplier<List<MasterDataItem>> mirrorFallback) {
        List<MasterDataItem> live = schemaClient != null ? schemaClient.list(kind) : Collections.emptyList();
        if (live != null && !live.isEmpty()) {
            return live;
        }
        if (masterDataService == null) {
            return Collections.emptyList();
        }
        return mirrorFallback.get();
    }

    @GetMapping("/companies")
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<List<MasterDataItem>> listCompanies() {
        return ResponseEntity.ok(resolve("companies", masterDataService::listCompanies));
    }

    @GetMapping("/projects")
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<List<MasterDataItem>> listProjects() {
        return ResponseEntity.ok(resolve("projects", masterDataService::listProjects));
    }

    @GetMapping("/branches")
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<List<MasterDataItem>> listBranches() {
        return ResponseEntity.ok(resolve("branches", masterDataService::listBranches));
    }

    @GetMapping("/departments")
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<List<MasterDataItem>> listDepartments() {
        return ResponseEntity.ok(resolve("departments", masterDataService::listDepartments));
    }
}
