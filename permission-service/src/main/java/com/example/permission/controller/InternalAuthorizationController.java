package com.example.permission.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal authorization endpoint (Faz 23.1 PR5 — Codex 019dfaaa absorb).
 *
 * <p>S2S authorize check with arbitrary principal type. Used by
 * notification-orchestrator to check whether a notification recipient
 * (subscriber or external) is authorized to receive a specific template.
 *
 * <p>Why internal-only:
 * <ul>
 *   <li>Existing {@code /api/v1/authz/check} resolves principal from JWT
 *       (user-bound). Notification authz needs arbitrary principal
 *       (e.g., subscriber:1204 from intent payload, not requesting user).</li>
 *   <li>Service-to-service auth via {@code X-Internal-Api-Key} header
 *       (handled by {@code InternalApiKeyAuthFilter} → {@code ROLE_INTERNAL}).</li>
 * </ul>
 *
 * <p>Request:
 * <pre>
 *   POST /api/v1/internal/authz/check
 *   X-Internal-Api-Key: ***
 *   { "principal_type": "subscriber", "principal_id": "1204",
 *     "relation": "can_receive", "object_type": "template",
 *     "object_id": "auth-password-reset" }
 * </pre>
 *
 * <p>Response:
 * <pre>
 *   200 OK
 *   { "allowed": true, "reason": "tuple_match" }
 * </pre>
 *
 * <p>Principal type → OpenFGA user-ref mapping:
 * <ul>
 *   <li>{@code subscriber} → {@code subscriber:{id}}</li>
 *   <li>{@code external} → {@code external:{id}} (id = email hash)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/internal/authz")
public class InternalAuthorizationController {

    private static final Logger log = LoggerFactory.getLogger(InternalAuthorizationController.class);

    private final OpenFgaAuthzService authzService;

    public InternalAuthorizationController(OpenFgaAuthzService authzService) {
        this.authzService = authzService;
    }

    @PostMapping("/check")
    @PreAuthorize("hasRole('INTERNAL')")
    public ResponseEntity<Map<String, Object>> check(@Valid @RequestBody InternalAuthzCheckRequest request) {
        String principalRef = request.principalType() + ":" + request.principalId();
        boolean allowed = authzService.checkPrincipal(
            principalRef, request.relation(), request.objectType(), request.objectId()
        );
        log.debug("internal authz check: {} {} {}:{} → {}",
            principalRef, request.relation(), request.objectType(),
            request.objectId(), allowed);
        return ResponseEntity.ok(Map.of(
            "allowed", allowed,
            "reason", allowed ? "tuple_match" : "no_tuple"
        ));
    }

    /**
     * Internal authz check request DTO.
     *
     * <p>Validation:
     * <ul>
     *   <li>principal_type: subscriber | external (lowercase, regex)</li>
     *   <li>principal_id: alphanumeric + dash/underscore (no colons —
     *       avoid OpenFGA user-ref ambiguity)</li>
     *   <li>relation, object_type, object_id: non-blank, length-bounded</li>
     * </ul>
     */
    public record InternalAuthzCheckRequest(
        @NotBlank
        @Pattern(regexp = "^(subscriber|external)$",
                 message = "principal_type must be 'subscriber' or 'external'")
        String principalType,

        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$",
                 message = "principal_id must be alphanumeric (no colons or special chars)")
        String principalId,

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[a-z][a-z0-9_]*$",
                 message = "relation must be lowercase identifier")
        String relation,

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[a-z][a-z0-9_]*$",
                 message = "object_type must be lowercase identifier")
        String objectType,

        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "^[a-zA-Z0-9_.-]+$",
                 message = "object_id must be alphanumeric/dot/dash/underscore")
        String objectId
    ) {}
}
