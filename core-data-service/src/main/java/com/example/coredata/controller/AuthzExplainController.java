package com.example.coredata.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.scope.ScopeContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

        boolean allowed = authzService.check(
                userId,
                request.relation(),
                request.objectType(),
                request.objectId()
        );

        return ResponseEntity.ok(Map.of("allowed", allowed));
    }
}
