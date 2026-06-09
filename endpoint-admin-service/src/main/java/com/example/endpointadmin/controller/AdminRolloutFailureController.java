package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureDetailResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureItemResponse;
import com.example.endpointadmin.dto.v1.admin.WaveFailureQueueReportResponse;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.RolloutFailureQueueReadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * #527 slice-1 — READ surface over the failed-device rollout queue. GET-only;
 * there is NO write/mutation endpoint in slice-1 (ingest is slice-2; waive/
 * resolve/retry are later). The wave report carries
 * {@code thresholdEvaluation.available=false} — slice-1 never claims an enforced
 * stop-line. Org-scoped via {@link TenantContextResolver}; read authz only.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
public class AdminRolloutFailureController {

    private final RolloutFailureQueueReadService readService;
    private final TenantContextResolver tenantContextResolver;

    public AdminRolloutFailureController(RolloutFailureQueueReadService readService,
                                         TenantContextResolver tenantContextResolver) {
        this.readService = readService;
        this.tenantContextResolver = tenantContextResolver;
    }

    /** Active queue items for a wave, optionally filtered by class / device. */
    @GetMapping("/endpoint-rollout-failures")
    public ResponseEntity<List<RolloutFailureItemResponse>> listActive(
            @RequestParam("rolloutId") String rolloutId,
            @RequestParam("waveId") String waveId,
            @RequestParam(value = "class", required = false) String classFilter,
            @RequestParam(value = "deviceId", required = false) UUID deviceFilter) {
        UUID tenantId = tenantContextResolver.resolveRequired().tenantId();
        RolloutFailureClass parsedClass = parseClass(classFilter);
        return ResponseEntity.ok(readService.listActive(tenantId, rolloutId, waveId, parsedClass, deviceFilter));
    }

    /** A queue item plus its ordered append-only event ledger. */
    @GetMapping("/endpoint-rollout-failures/{failureId}")
    public ResponseEntity<RolloutFailureDetailResponse> detail(@PathVariable("failureId") UUID failureId) {
        UUID tenantId = tenantContextResolver.resolveRequired().tenantId();
        return readService.getDetail(tenantId, failureId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "rollout failure not found: " + failureId));
    }

    /** Wave failure-queue projection report (active counts; evaluator deferred). */
    @GetMapping("/endpoint-rollouts/{rolloutId}/waves/{waveId}/failure-queue-report")
    public ResponseEntity<WaveFailureQueueReportResponse> waveReport(
            @PathVariable("rolloutId") String rolloutId,
            @PathVariable("waveId") String waveId) {
        UUID tenantId = tenantContextResolver.resolveRequired().tenantId();
        return ResponseEntity.ok(readService.waveReport(tenantId, rolloutId, waveId, Instant.now()));
    }

    private static RolloutFailureClass parseClass(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RolloutFailureClass.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "unknown failure class: " + value);
        }
    }
}
