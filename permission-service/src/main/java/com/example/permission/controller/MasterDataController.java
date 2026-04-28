package com.example.permission.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.permission.dataaccess.MasterDataItem;
import com.example.permission.dataaccess.MasterDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

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

    @Autowired(required = false)
    public MasterDataController(@Nullable MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @GetMapping("/companies")
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<List<MasterDataItem>> listCompanies() {
        return ResponseEntity.ok(masterDataService != null
                ? masterDataService.listCompanies()
                : Collections.emptyList());
    }

    @GetMapping("/projects")
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<List<MasterDataItem>> listProjects() {
        return ResponseEntity.ok(masterDataService != null
                ? masterDataService.listProjects()
                : Collections.emptyList());
    }

    @GetMapping("/branches")
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<List<MasterDataItem>> listBranches() {
        return ResponseEntity.ok(masterDataService != null
                ? masterDataService.listBranches()
                : Collections.emptyList());
    }

    @GetMapping("/departments")
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<List<MasterDataItem>> listDepartments() {
        return ResponseEntity.ok(masterDataService != null
                ? masterDataService.listDepartments()
                : Collections.emptyList());
    }
}
