package com.example.permission.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.permission.dto.AuditCompareResponse;
import com.example.permission.service.AuditCompareService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shadow-compare diagnostic endpoint — calls BOTH permission-service (self)
 * and user-service (over HTTP), returns both responses + a diff summary.
 *
 * Used to collect parity evidence before operators flip AUDIT_BACKEND_URI
 * (Faz 1) to permission-service. Gated on AUDIT.manager (strictest audit
 * relation) so only operators/admins can poll this diagnostic.
 *
 * 2026-04-20 QLTY-PROACTIVE-06 Faz 2.
 *
 * Typical usage (stage, as admin):
 *   curl -s -H "Authorization: Bearer <admin-token>" \
 *     "https://ai.acik.com/api/audit/events/compare?page=1&pageSize=50" | jq .
 *
 * Integrates with staging-error-sweep workflow (future): a lane can invoke
 * this endpoint daily and fail if verdict != "clean" for N consecutive runs.
 */
@RestController
@RequestMapping("/api/audit/events/compare")
public class AuditCompareController {

    private final AuditCompareService auditCompareService;

    public AuditCompareController(AuditCompareService auditCompareService) {
        this.auditCompareService = auditCompareService;
    }

    @GetMapping
    @RequireModule(value = "AUDIT", relation = "manager")
    public ResponseEntity<AuditCompareResponse> compare(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            HttpServletRequest request
    ) {
        // 2026-04-20 Faz 2 post-deploy: user-service's /api/audit/events requires
        // an authenticated caller even though its controller has no @RequireModule.
        // Forward the caller's Authorization header so the compare can reach both
        // upstreams consistently. (Alternative: mint a service-token via auth-
        // service — heavier; forwarding suffices for stage evidence collection.)
        String authHeader = request.getHeader("Authorization");
        return ResponseEntity.ok(auditCompareService.compare(page, pageSize, authHeader));
    }
}
