package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryDiffResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryItemResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventorySnapshotResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryStateHistoryResponse;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.model.SoftwareInstallSource;
import com.example.endpointadmin.repository.EndpointSoftwareInventoryItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointSoftwareInventoryDiffService;
import com.example.endpointadmin.service.EndpointSoftwareInventoryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * BE-020I — Software Inventory admin REST surface (Faz 22.5.3A).
 *
 * <p>Reuses the {@code module:endpoint-admin} {@code can_view} relation via
 * {@code @RequireModule(VIEWER)} — no new OpenFGA scope opened
 * (Codex 019e6ab2 iter-2 plan acceptance).
 *
 * <p>Two routes:
 * <ul>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/software-inventory}
 *       — single device summary + paged items (optional {@code q} substring
 *       filter on display name, {@code publisher} exact match, {@code
 *       installSource} enum filter).</li>
 *   <li>{@code GET /api/v1/admin/endpoint-software-inventory} — fleet-wide
 *       paged snapshot summary; {@code softwareName} presence makes the row
 *       only appear when the device's items contain that app.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointSoftwareInventoryController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    /** BE-024 history view — a per-device chronological scrub, not a fleet
     *  search; a narrower window than the item list (50/200) is the right
     *  ergonomic (mirrors the BE-022Q hardware history 20/50). */
    private static final int HISTORY_DEFAULT_PAGE_SIZE = 20;
    private static final int HISTORY_MAX_PAGE_SIZE = 50;

    private final EndpointSoftwareInventoryService inventoryService;
    private final EndpointSoftwareInventoryDiffService diffService;
    private final EndpointSoftwareInventoryItemRepository itemRepository;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointSoftwareInventoryController(
            EndpointSoftwareInventoryService inventoryService,
            EndpointSoftwareInventoryDiffService diffService,
            EndpointSoftwareInventoryItemRepository itemRepository,
            TenantContextResolver tenantContextResolver) {
        this.inventoryService = inventoryService;
        this.diffService = diffService;
        this.itemRepository = itemRepository;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/software-inventory")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public DeviceSoftwareInventoryPayload getDeviceSnapshot(
            @PathVariable UUID deviceId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) SoftwareInstallSource installSource,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        EndpointSoftwareInventorySnapshot snapshot =
                inventoryService.requireDeviceSnapshot(context, deviceId);

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.max(1, Math.min(size, MAX_PAGE_SIZE)));
        Page<AdminSoftwareInventoryItemResponse> items = itemRepository
                .pageByTenantDeviceWithFilters(
                        context.tenantId(),
                        deviceId,
                        trimToNull(q),
                        trimToNull(publisher),
                        installSource,
                        pageable)
                .map(AdminSoftwareInventoryItemResponse::from);

        return new DeviceSoftwareInventoryPayload(
                AdminSoftwareInventorySnapshotResponse.from(snapshot),
                items);
    }

    /**
     * BE-024 — latest-vs-previous software-inventory diff (added / removed /
     * version-changed apps) for a device.
     *
     * <p>Always 200 (never 404) so a cross-tenant / unknown device is
     * indistinguishable from "no history yet" — device existence does not
     * leak (mirrors the BE-022Q no-leak discipline). The response
     * {@code status} field carries OK / NO_CHANGE / INSUFFICIENT_HISTORY /
     * NO_HISTORY. {@code @Transactional(readOnly=true)} keeps the session
     * open while the service walks the stored JSONB digests
     * ({@code spring.jpa.open-in-view=false}).
     *
     * <p>STRICT v1: ADDED / REMOVED / VERSION_CHANGED only. "Outdated"
     * deltas are out of scope (BE-024b).
     */
    @GetMapping("/endpoint-devices/{deviceId}/software-inventory/diff")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public AdminSoftwareInventoryDiffResponse getDeviceSoftwareDiff(
            @PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return diffService.diffLatest(context, deviceId);
    }

    /**
     * BE-024 — paged append-only software-state capture history for a
     * device (summary projection; no per-app digest on the wire). Empty
     * page is the canonical no-data answer (no 404).
     */
    @GetMapping("/endpoint-devices/{deviceId}/software-inventory/history")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public Page<AdminSoftwareInventoryStateHistoryResponse> getDeviceSoftwareHistory(
            @PathVariable UUID deviceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return diffService.history(
                        context,
                        deviceId,
                        Math.max(0, page),
                        clampHistoryPageSize(size))
                .map(AdminSoftwareInventoryStateHistoryResponse::from);
    }

    @GetMapping("/endpoint-software-inventory")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public Page<AdminSoftwareInventorySnapshotResponse> listFleetSnapshots(
            @RequestParam(required = false) String softwareName,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) Boolean wingetReady,
            @RequestParam(required = false) Boolean truncated,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                Math.max(1, Math.min(size, MAX_PAGE_SIZE)));
        return inventoryService.pageFleet(
                context,
                trimToNull(softwareName),
                trimToNull(publisher),
                wingetReady,
                truncated,
                pageable)
                .map(AdminSoftwareInventorySnapshotResponse::from);
    }

    /**
     * Wire shape for the device-detail endpoint: snapshot summary +
     * paged items.
     */
    public record DeviceSoftwareInventoryPayload(
            AdminSoftwareInventorySnapshotResponse snapshot,
            Page<AdminSoftwareInventoryItemResponse> items
    ) {
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Clamp the history page size into [1, {@link #HISTORY_MAX_PAGE_SIZE}];
     *  a zero/negative request collapses to {@link #HISTORY_DEFAULT_PAGE_SIZE}
     *  (mirrors the BE-022Q hardware history clamp). */
    static int clampHistoryPageSize(int requested) {
        if (requested <= 0) {
            return HISTORY_DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, HISTORY_MAX_PAGE_SIZE);
    }
}
