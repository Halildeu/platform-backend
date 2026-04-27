package com.example.permission.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.permission.dataaccess.AccessScopeService;
import com.example.permission.dataaccess.DataAccessScope;
import com.example.permission.dataaccess.DataAccessScopeOutboxEntry;
import com.example.permission.dataaccess.DataAccessScopeTupleEncoder;
import com.example.permission.dto.access.ScopeGrantRequest;
import com.example.permission.dto.access.ScopeGrantResponse;
import com.example.permission.dto.access.ScopeListItem;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 21.3 PR-D / PR-G — REST surface for {@code data_access.scope}.
 *
 * <p>Endpoints (unchanged from PR-D):
 * <ul>
 *   <li>{@code POST /api/v1/access/scope} — grant (201 + ScopeGrantResponse, now with outbox fields)</li>
 *   <li>{@code DELETE /api/v1/access/scope/{id}} — revoke (204)</li>
 *   <li>{@code GET /api/v1/access/scope?userId=&orgId=} — list (200)</li>
 * </ul>
 *
 * <p>PR-G outbox: the response body now includes {@code tupleSyncStatus},
 * {@code outboxId} and {@code processedAt} (additive — old clients ignore
 * unknown fields). The status starts at {@code "PENDING"} and reflects the
 * outbox row state at the time the grant TX commits; the UI / caller can
 * poll the outbox to learn when the FGA tuple is actually written.
 *
 * <p>Activation: the controller bean is always registered, but
 * {@link AccessScopeService} is injected as
 * {@code Optional<AccessScopeService>}. When either of
 * {@code REPORTS_DB_ENABLED} / {@code erp.openfga.enabled} is false (the
 * service's multi-name {@code @ConditionalOnProperty}), the dependency is
 * empty and every endpoint returns 503 — keeping the application bootable
 * in environments where the secondary {@code reports_db} datasource or
 * OpenFGA itself is not provisioned.
 */
@RestController
@RequestMapping("/api/v1/access/scope")
public class AccessScopeController {

    private final AccessScopeService accessScopeService;

    public AccessScopeController(Optional<AccessScopeService> accessScopeServiceOpt) {
        this.accessScopeService = accessScopeServiceOpt.orElse(null);
    }

    @PostMapping
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<ScopeGrantResponse> grant(@Valid @RequestBody ScopeGrantRequest request) {
        if (accessScopeService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        AccessScopeService.ScopeMutationResult result = accessScopeService.grant(
                request.userId(),
                request.orgId(),
                request.scopeKind(),
                request.scopeRef(),
                request.grantedBy()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @DeleteMapping("/{id}")
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<Void> revoke(@PathVariable Long id,
                                       @RequestParam(value = "revokedBy", required = false) UUID revokedBy) {
        if (accessScopeService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        accessScopeService.revoke(id, revokedBy);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<List<ScopeListItem>> list(@RequestParam UUID userId,
                                                    @RequestParam Long orgId) {
        if (accessScopeService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        List<ScopeListItem> items = accessScopeService.listActiveScopes(userId, orgId).stream()
                .map(s -> new ScopeListItem(
                        s.getId(),
                        s.getUserId(),
                        s.getOrgId(),
                        s.getScopeKind().name(),
                        s.getScopeRef(),
                        s.getGrantedAt(),
                        s.isActive()
                ))
                .toList();
        return ResponseEntity.ok(items);
    }

    private static ScopeGrantResponse toResponse(AccessScopeService.ScopeMutationResult result) {
        DataAccessScope scope = result.scope();
        DataAccessScopeOutboxEntry outbox = result.outboxEntry();
        DataAccessScopeTupleEncoder.FgaTuple tuple = DataAccessScopeTupleEncoder.encode(scope);
        return new ScopeGrantResponse(
                scope.getId(),
                scope.getUserId(),
                scope.getOrgId(),
                scope.getScopeKind().name(),
                scope.getScopeRef(),
                scope.getGrantedAt(),
                tuple.objectType(),
                tuple.objectId(),
                outbox.getStatus().name(),
                outbox.getId(),
                outbox.getProcessedAt()
        );
    }
}
