package com.example.permission.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.permission.dataaccess.AccessScopeService;
import com.example.permission.dataaccess.DataAccessScope;
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
 * Faz 21.3 PR-D: REST surface for {@code data_access.scope} per ADR-0008
 * explicit-scope contract.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/v1/access/scope} — grant (201 + ScopeGrantResponse)</li>
 *   <li>{@code DELETE /api/v1/access/scope/{id}} — revoke (204)</li>
 *   <li>{@code GET /api/v1/access/scope?userId=&orgId=} — list (200)</li>
 * </ul>
 *
 * <p>Activation: the controller bean is always registered, but the
 * {@link AccessScopeService} dependency is injected as
 * {@code Optional<AccessScopeService>}. When {@code REPORTS_DB_ENABLED=false}
 * the service bean is absent and every endpoint short-circuits to
 * {@link HttpStatus#SERVICE_UNAVAILABLE} (503). This keeps the application
 * bootable in environments where the secondary {@code reports_db} datasource
 * is not provisioned, while still letting clients distinguish "scope service
 * is off" (503) from "no such route" (404) and "no such scope id" (404 from
 * {@link AccessScopeExceptionHandler}).
 *
 * <p>Authorization: {@link RequireModule} on the {@code ACCESS} module
 * (existing OpenFGA-backed authz pattern). Grant/revoke require {@code admin};
 * list requires {@code viewer}. ADR-0008 specifies {@code organization#admin}
 * for scope assignment authority — we map that onto the existing
 * {@code ACCESS} module admin relation rather than introducing a parallel
 * authz vocabulary.
 */
@RestController
@RequestMapping("/api/v1/access/scope")
public class AccessScopeController {

    private final AccessScopeService accessScopeService;

    public AccessScopeController(Optional<AccessScopeService> accessScopeServiceOpt) {
        this.accessScopeService = accessScopeServiceOpt.orElse(null);
    }

    @PostMapping
    @RequireModule(value = "ACCESS", relation = "admin")
    public ResponseEntity<ScopeGrantResponse> grant(@Valid @RequestBody ScopeGrantRequest request) {
        if (accessScopeService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        DataAccessScope scope = accessScopeService.grant(
                request.userId(),
                request.orgId(),
                request.scopeKind(),
                request.scopeRef(),
                request.grantedBy()
        );
        DataAccessScopeTupleEncoder.FgaTuple tuple = DataAccessScopeTupleEncoder.encode(scope);
        ScopeGrantResponse response = new ScopeGrantResponse(
                scope.getId(),
                scope.getUserId(),
                scope.getOrgId(),
                scope.getScopeKind().name(),
                scope.getScopeRef(),
                scope.getGrantedAt(),
                tuple.objectType(),
                tuple.objectId()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    @RequireModule(value = "ACCESS", relation = "admin")
    public ResponseEntity<Void> revoke(@PathVariable Long id,
                                       @RequestParam(value = "revokedBy", required = false) UUID revokedBy) {
        if (accessScopeService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        accessScopeService.revoke(id, revokedBy);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @RequireModule(value = "ACCESS", relation = "viewer")
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
}
