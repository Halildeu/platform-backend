package com.example.permission.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.permission.dto.access.AccessRoleDto;
import com.example.permission.dto.v1.BulkPermissionsRequestDto;
import com.example.permission.dto.v1.BulkPermissionsResponseDto;
import com.example.permission.dto.v1.CloneRoleRequestDto;
import com.example.permission.dto.v1.CreateRoleRequestDto;
import com.example.permission.dto.v1.PagedResultDto;
import com.example.permission.dto.v1.PermissionDtoMapper;
import com.example.permission.dto.v1.RoleCloneResponseDto;
import com.example.permission.dto.v1.RoleDto;
import com.example.permission.dto.v1.RolePermissionsUpdateRequestDto;
import com.example.permission.dto.v1.RolePermissionsUpdateResponseDto;
import com.example.permission.dto.v1.ScopeAssignmentRequestDto;
import com.example.permission.dto.v1.ScopeSummaryDto;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.service.AccessRoleService;
import com.example.permission.service.PermissionService;
import com.example.permission.service.TupleSyncService;
import com.example.permission.service.UserScopeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.example.permission.model.PermissionModel;
import com.example.permission.model.Role;

import java.util.List;
import java.util.Map;

/**
 * v1 roles endpoint'leri; STORY-0318: members, granules eklendi.
 */
@RestController
@RequestMapping("/api/v1/roles")
public class AccessControllerV1 {

    private final AccessRoleService accessRoleService;
    private final UserScopeService userScopeService;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RoleRepository roleRepository;
    private final PermissionService permissionService;
    private final TupleSyncService tupleSyncService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public AccessControllerV1(AccessRoleService accessRoleService,
                              UserScopeService userScopeService,
                              UserRoleAssignmentRepository assignmentRepository,
                              RolePermissionRepository rolePermissionRepository,
                              RoleRepository roleRepository,
                              PermissionService permissionService,
                              @org.springframework.lang.Nullable TupleSyncService tupleSyncService,
                              org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.accessRoleService = accessRoleService;
        this.userScopeService = userScopeService;
        this.assignmentRepository = assignmentRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.roleRepository = roleRepository;
        this.permissionService = permissionService;
        this.tupleSyncService = tupleSyncService;
        this.eventPublisher = eventPublisher;
    }

    @GetMapping
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<PagedResultDto<RoleDto>> listRoles() {
        List<AccessRoleDto> roles = accessRoleService.listRoles();
        List<RoleDto> items = roles.stream().map(PermissionDtoMapper::toRoleDto).toList();
        return ResponseEntity.ok(PermissionDtoMapper.wrap(items, items.size()));
    }

    @GetMapping("/{roleId}")
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<RoleDto> getRole(@PathVariable Long roleId) {
        AccessRoleDto role = accessRoleService.getRole(roleId);
        return ResponseEntity.ok(PermissionDtoMapper.toRoleDto(role));
    }

    @PostMapping
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<RoleDto> createRole(@RequestBody CreateRoleRequestDto request) {
        if (request.name() == null || request.name().trim().length() < 3) {
            return ResponseEntity.badRequest().build();
        }
        RoleDto created = accessRoleService.createRole(
                request.name().trim(),
                request.description(),
                null
        );
        return ResponseEntity.status(201).body(created);
    }

    @DeleteMapping("/{roleId}")
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<Void> deleteRole(@PathVariable Long roleId,
                                           @RequestParam(value = "performedBy", required = false) Long performedBy) {
        accessRoleService.deleteRole(roleId, performedBy);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{roleId}/clone")
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<RoleCloneResponseDto> cloneRole(@PathVariable Long roleId,
                                                         @RequestBody(required = false) CloneRoleRequestDto request) {
        CloneRoleRequestDto payload = request == null ? new CloneRoleRequestDto() : request;
        RoleCloneResponseDto result = accessRoleService.cloneRole(
                roleId,
                payload.getName(),
                payload.getDescription(),
                payload.getPerformedBy()
        );
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/{roleId}/permissions/bulk")
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<BulkPermissionsResponseDto> bulkPermissions(@PathVariable Long roleId,
                                                                @RequestBody BulkPermissionsRequestDto request) {
        rejectIfGranuleManaged(roleId);
        BulkPermissionsResponseDto result = accessRoleService.bulkUpdateModuleLevel(
                List.of(roleId),
                request.getModuleKey(),
                request.getModuleLabel(),
                request.getLevel(),
                request.getPerformedBy()
        );
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{roleId}/permissions")
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<RolePermissionsUpdateResponseDto> updateRolePermissions(@PathVariable Long roleId,
                                                                                  @RequestBody(required = false) RolePermissionsUpdateRequestDto request) {
        rejectIfGranuleManaged(roleId);
        RolePermissionsUpdateRequestDto payload = request == null ? new RolePermissionsUpdateRequestDto() : request;
        RolePermissionsUpdateResponseDto result = accessRoleService.updateRolePermissions(
                roleId,
                payload.getPermissionIds(),
                payload.getPerformedBy()
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Codex 019dd818 iter-16 + iter-17 (Plan C): legacy write boundary
     * enforcement with marker-OR-row-shape drift guard.
     *
     * <p>Granule-managed roles (those flipped to {@link PermissionModel#GRANULE}
     * by {@code PUT /granules}) MUST not be modified through the legacy
     * code-based endpoints. Mixing the two writers re-creates the FK + granule
     * mixed state V15/V16 cleaned up. Returning 409 surfaces the boundary to
     * any caller still pointing at legacy endpoints; the canonical path is
     * {@code PUT /api/v1/roles/{id}/granules}.
     *
     * <p>iter-17 mirrors the {@code marker OR row-shape} predicate already
     * used in
     * {@link com.example.permission.config.PermissionDataInitializer#usesGranuleModel}.
     * If a role's {@code permission_model} marker drifted to LEGACY but the
     * table still has granule-shape rows (e.g. manual SQL, partial backfill,
     * future schema change), the legacy endpoints must still reject writes
     * — otherwise the system can re-enter mixed state asymmetrically.
     */
    private void rejectIfGranuleManaged(Long roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Role not found: " + roleId));
        boolean granuleManaged = role.getPermissionModel() == PermissionModel.GRANULE
                || rolePermissionRepository.existsGranuleShapeByRoleId(roleId);
        if (granuleManaged) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Role " + roleId + " is granule-managed; use PUT /api/v1/roles/"
                            + roleId + "/granules instead of legacy permission endpoints");
        }
    }

    /**
     * Get users assigned to a role (bidirectional: role → users).
     */
    @GetMapping("/{roleId}/members")
    public ResponseEntity<List<Map<String, Object>>> getRoleMembers(@PathVariable Long roleId) {
        var assignments = assignmentRepository.findByRoleIdAndActiveTrue(roleId);
        List<Map<String, Object>> members = assignments.stream()
                .map(a -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("userId", a.getUserId());
                    m.put("assignedAt", a.getAssignedAt() != null ? a.getAssignedAt().toString() : null);
                    return m;
                })
                .toList();
        return ResponseEntity.ok(members);
    }

    /**
     * Add user(s) to a role (bidirectional: role → user assignment).
     */
    @PostMapping("/{roleId}/members")
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<Map<String, Object>> addRoleMembers(@PathVariable Long roleId,
                                                               @RequestBody Map<String, List<Long>> body) {
        List<Long> userIds = body.getOrDefault("userIds", List.of());
        for (Long userId : userIds) {
            var existing = assignmentRepository.findActiveAssignment(userId, null, roleId, null, null);
            if (existing.isEmpty()) {
                var req = new com.example.permission.dto.PermissionAssignRequest();
                req.setUserId(userId);
                req.setRoleId(roleId);
                permissionService.assignRole(req);
            }
        }
        return ResponseEntity.ok(Map.of("roleId", roleId, "addedUserIds", userIds));
    }

    /**
     * Remove a user from a role.
     */
    @DeleteMapping("/{roleId}/members/{userId}")
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<Void> removeRoleMember(@PathVariable Long roleId, @PathVariable Long userId) {
        var assignments = assignmentRepository.findActiveAssignments(userId);
        assignments.stream()
                .filter(a -> a.getRole().getId().equals(roleId))
                .forEach(a -> permissionService.revokeRole(a.getId(), null));
        return ResponseEntity.noContent().build();
    }

    /**
     * Update role permissions with 5-granule format + DENY support.
     * Triggers tuple propagation for all assigned users.
     */
    /**
     * Codex 019dda05 iter-25: typed read endpoint for role granules.
     * Companion to {@link #updateRoleGranules}; the role drawer's source-of-
     * truth query. Returns every granule-shape row (MODULE / ACTION / REPORT)
     * for the role, deterministically ordered.
     *
     * <p>Read-after-write semantics: a successful {@code PUT /granules}
     * followed by this {@code GET /granules} yields exactly the granules
     * just saved. This closes the regression where mfe-access drawer's
     * REPORT/ACTION selects rendered "Yetki Yok" after a save round-trip
     * because {@code GET /v1/roles/{id}} (RoleDto) only exposed module-
     * level summaries in {@code policies}.
     *
     * <p>Filter: {@code rp.getPermission() == null} (granule rows only).
     * Legacy FK rows remain exposed via {@code AccessRoleDto.permissions}.
     *
     * <p>Sort: MODULE → ACTION → REPORT, then alphabetic by key. Stable
     * across reloads so drawer UI doesn't flicker.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    @GetMapping("/{roleId}/granules")
    @RequireModule(value = "ACCESS", relation = "can_view")
    public ResponseEntity<com.example.permission.dto.v1.RoleGranulesDto> getRoleGranules(@PathVariable Long roleId) {
        var role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Role not found: " + roleId));

        java.util.Comparator<com.example.permission.model.RolePermission> typeOrder =
                java.util.Comparator.comparingInt(rp -> {
                    var t = rp.getPermissionType();
                    if (t == null) return Integer.MAX_VALUE;
                    return switch (t) {
                        case MODULE -> 0;
                        case ACTION -> 1;
                        case REPORT -> 2;
                    };
                });
        java.util.Comparator<com.example.permission.model.RolePermission> keyOrder =
                java.util.Comparator.comparing(
                        com.example.permission.model.RolePermission::getPermissionKey,
                        java.util.Comparator.nullsLast(String::compareTo));

        List<com.example.permission.dto.v1.RolePermissionItemDto> granules =
                role.getRolePermissions().stream()
                        .filter(rp -> rp.getPermission() == null)
                        .filter(rp -> rp.getPermissionType() != null)
                        .filter(rp -> rp.getPermissionKey() != null && !rp.getPermissionKey().isBlank())
                        .filter(rp -> rp.getGrantType() != null)
                        .sorted(typeOrder.thenComparing(keyOrder))
                        .map(rp -> new com.example.permission.dto.v1.RolePermissionItemDto(
                                rp.getPermissionType().name(),
                                rp.getPermissionKey(),
                                rp.getGrantType().name()
                        ))
                        .toList();

        return ResponseEntity.ok(new com.example.permission.dto.v1.RoleGranulesDto(roleId, granules));
    }

    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/{roleId}/granules")
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<Map<String, Object>> updateRoleGranules(
            @PathVariable Long roleId,
            @RequestBody Map<String, List<com.example.permission.dto.v1.RolePermissionItemDto>> body) {
        // Codex 019dd9f0 iter-22 (A++ hotfix): SELECT … FOR UPDATE serializes
        // concurrent /granules calls on the same role at the parent row. Without
        // this lock two requests can race on the "clear → flush → insert" path
        // and one will crash on the partial unique index
        // uk_role_permissions_role_granule.
        var role = roleRepository.findByIdForUpdate(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));

        var items = body.getOrDefault("permissions", List.of());

        // Codex 019dd9f0 iter-22: validate + canonicalize input.
        // RolePermission entity has no business-key equals/hashCode and the
        // partial unique index treats every (role_id, permission_type,
        // permission_key) tuple as unique. If the client sends the same
        // (type, key) twice in one payload, our aggregate accepts both and
        // the second insert collides on commit. Reject malformed input
        // explicitly with 400 instead of letting Hibernate translate it
        // into a 500 from a constraint violation.
        var normalized = validateAndNormalizeGranules(items);

        // Codex 019dd818 iter-13 (Plan B): aggregate-native granule replace.
        // Önceki implementasyon `rolePermissionRepository.deleteByRoleId` (JPQL
        // @Modifying bulk DELETE) + ardından `rolePermissionRepository.save(rp)`
        // + `roleRepository.save(role)` cascade = ALL pattern'i kullanıyordu.
        // Bulk DELETE persistence context'i bypass ettiği için role.rolePermissions
        // collection'unda eski entity'ler kalıyor; `roleRepository.save(role)`
        // cascade-persist ile bu eski rows DB'ye yeniden insert ediyordu.
        // Sonuç: USER_MANAGE save sonrası 4 row (1 yeni granule + 3 cascade-
        // resurrected eski FK) — kullanıcı VIEW yazarken drawer MANAGE gösteriyor
        // (deriveLevel eski FK code'ları üzerinden 'admin' contains).
        //
        // Plan B: aggregate üzerinden replace. role.clearRolePermissions() →
        // orphanRemoval=true ile DB DELETE; role.addRolePermission(rp) →
        // cascade=ALL ile DB INSERT. Tek transaction, tek persistence context,
        // bulk DML yok.
        role.clearRolePermissions();

        // Codex 019dd9f0 iter-22 (A++ hotfix): force orphan-removal DELETEs to
        // be flushed to the database BEFORE we add the new granules. Without
        // this explicit flush, Hibernate's default action ordering (or the
        // managed Set semantics) can let the new INSERTs reach Postgres before
        // the pending DELETEs do, producing a duplicate-key violation on the
        // partial unique index when the saved key set overlaps with the
        // existing one (e.g. "USER_MANAGEMENT MANAGE" → "USER_MANAGEMENT VIEW"
        // is still the same (role_id, permission_type, permission_key) tuple).
        // Failure is observed in production as HTTP 500 from
        // "duplicate key value violates unique constraint
        // uk_role_permissions_role_granule".
        roleRepository.flush();

        for (var item : normalized) {
            role.addRolePermission(new com.example.permission.model.RolePermission(
                    role,
                    com.example.permission.model.PermissionType.valueOf(item.type().toUpperCase(java.util.Locale.ROOT)),
                    item.key(),
                    com.example.permission.model.GrantType.valueOf(item.grant().toUpperCase(java.util.Locale.ROOT))
            ));
        }

        // Codex 019dd818 iter-16 (Plan C): explicitly mark the role as granule-
        // managed on every /granules call, including empty replace. Without
        // this flip, an empty replace leaves rolePermissions=Ø; on next boot
        // PermissionDataInitializer's row-shape predicate misclassifies the
        // role as legacy and re-seeds DEFAULT_ROLE_PERMISSIONS FK rows. The
        // marker survives the empty state and keeps the seed flow off.
        role.setPermissionModel(com.example.permission.model.PermissionModel.GRANULE);

        role.setUpdatedAt(java.time.Instant.now());
        roleRepository.save(role);

        // CNS-002 #2-3: Publish event — handled AFTER_COMMIT to avoid stale state
        eventPublisher.publishEvent(new com.example.permission.event.RoleChangeEvent(roleId));

        return ResponseEntity.ok(Map.of("roleId", roleId, "granuleCount", normalized.size(), "propagated", true));
    }

    /**
     * Codex 019dd9f0 iter-22 (A++ hotfix): validate that no two payload entries
     * share the same (type, key) tuple. The partial unique index
     * uk_role_permissions_role_granule treats this combination as the granule
     * identity, and the {@link com.example.permission.model.RolePermission}
     * entity does not implement business-key equals/hashCode — so the
     * aggregate's {@code Set<RolePermission>} would accept duplicates and the
     * second INSERT on commit would crash. Reject the request up front with a
     * meaningful 400 (instead of letting Hibernate translate it into a 500
     * "Beklenmeyen bir hata oluştu").
     *
     * <p>Also normalizes type/key/grant by trimming and uppercasing the type
     * and grant so callers can be lenient with case (the {@link
     * com.example.permission.model.PermissionType#valueOf} call later requires
     * an exact uppercase match).
     */
    private List<com.example.permission.dto.v1.RolePermissionItemDto>
            validateAndNormalizeGranules(List<com.example.permission.dto.v1.RolePermissionItemDto> items) {
        var seen = new java.util.HashSet<String>();
        var out = new java.util.ArrayList<com.example.permission.dto.v1.RolePermissionItemDto>(items.size());
        for (var item : items) {
            if (item == null
                    || item.type() == null || item.type().isBlank()
                    || item.key() == null || item.key().isBlank()
                    || item.grant() == null || item.grant().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Granule entry missing type/key/grant");
            }
            String typeUp = item.type().trim().toUpperCase(java.util.Locale.ROOT);
            String key = item.key().trim();
            String grantUp = item.grant().trim().toUpperCase(java.util.Locale.ROOT);
            String dedupKey = typeUp + "::" + key;
            if (!seen.add(dedupKey)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Duplicate granule (type=" + typeUp + ", key=" + key + ") in payload");
            }
            out.add(new com.example.permission.dto.v1.RolePermissionItemDto(typeUp, key, grantUp));
        }
        return out;
    }

    @GetMapping("/users/{userId}/scopes")
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<List<ScopeSummaryDto>> listUserScopes(@PathVariable Long userId) {
        return ResponseEntity.ok(userScopeService.listUserScopes(userId));
    }

    @PostMapping("/users/{userId}/scopes")
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<Void> addUserScope(@PathVariable Long userId,
                                             @RequestBody ScopeAssignmentRequestDto scope) {
        userScopeService.addScope(userId, scope.scopeType(), scope.scopeRefId(), scope.permissionCode());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/users/{userId}/scopes/{scopeType}/{scopeRefId}")
    @RequireModule(value = "ACCESS", relation = "can_manage")
    public ResponseEntity<Void> removeUserScope(@PathVariable Long userId,
                                                @PathVariable String scopeType,
                                                @PathVariable Long scopeRefId,
                                                @RequestParam("permissionCode") String permissionCode) {
        userScopeService.removeScope(userId, scopeType, scopeRefId, permissionCode);
        return ResponseEntity.noContent().build();
    }
}
