package com.example.coredata.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.scope.ScopeContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Explain endpoint — exposes OpenFGA check + expand for the "Why can't I access?" feature.
 * Lives in core-data-service because it has OpenFgaAuthzService injected.
 */
@RestController
@RequestMapping("/api/v1/authz")
public class AuthzExplainController {

    private final OpenFgaAuthzService authzService;

    public AuthzExplainController(OpenFgaAuthzService authzService) {
        this.authzService = authzService;
    }

    public record ExplainRequest(String relation, String objectType, String objectId) {}

    @PostMapping("/explain")
    public ResponseEntity<Map<String, Object>> explain(@RequestBody ExplainRequest request) {
        var scope = ScopeContextHolder.get();
        String userId = scope != null ? scope.userId() : "0";

        Map<String, Object> result = authzService.explainAccess(
                userId,
                request.relation(),
                request.objectType(),
                request.objectId()
        );

        return ResponseEntity.ok(result);
    }

    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> check(@RequestBody ExplainRequest request) {
        var scope = ScopeContextHolder.get();
        String userId = scope != null ? scope.userId() : "0";

        var result = authzService.checkWithReason(
                userId,
                request.relation(),
                request.objectType(),
                request.objectId()
        );

        return ResponseEntity.ok(Map.of(
                "allowed", result.allowed(),
                "reason", result.reason()
        ));
    }

    /**
     * Batch check endpoint — multiple object-level checks in a single request.
     * CNS-20260411-005: Codex REJECT (without batch) — max 20 per call.
     */
    public record BatchCheckRequest(List<ExplainRequest> checks) {}
    public record BatchCheckItem(boolean allowed, String reason,
                                 String relation, String objectType, String objectId) {}

    @PostMapping("/check/batch")
    public ResponseEntity<?> batchCheck(@RequestBody BatchCheckRequest request) {
        if (request.checks() == null || request.checks().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "checks array is required"));
        }
        if (request.checks().size() > 20) {
            return ResponseEntity.badRequest().body(Map.of("error", "Max 20 checks per batch request"));
        }

        var scope = ScopeContextHolder.get();
        String userId = scope != null ? scope.userId() : "0";

        List<BatchCheckItem> results = request.checks().stream()
                .map(c -> {
                    var result = authzService.checkWithReason(
                            userId, c.relation(), c.objectType(), c.objectId());
                    return new BatchCheckItem(
                            result.allowed(), result.reason(),
                            c.relation(), c.objectType(), c.objectId());
                })
                .toList();

        return ResponseEntity.ok(Map.of("results", results));
    }
}
