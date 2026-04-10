package com.example.permission.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.scope.ScopeContextCache;
import com.example.permission.model.GrantType;
import com.example.permission.model.PermissionType;
import com.example.permission.model.RolePermission;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Centralized OpenFGA tuple synchronization for the Zanzibar authorization model.
 * Handles feature-level (module/action/report/page/field) and data-level (scope) tuples.
 * Implements deny-wins semantics when combining permissions from multiple roles.
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "erp.openfga.enabled", havingValue = "true", matchIfMissing = false)
public class TupleSyncService {

    private static final Logger log = LoggerFactory.getLogger(TupleSyncService.class);

    private final OpenFgaAuthzService authzService;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final AuthzVersionService authzVersionService;
    private final ScopeContextCache scopeContextCache;

    @org.springframework.beans.factory.annotation.Autowired
    public TupleSyncService(OpenFgaAuthzService authzService,
                            RolePermissionRepository rolePermissionRepository,
                            UserRoleAssignmentRepository assignmentRepository,
                            AuthzVersionService authzVersionService,
                            @org.springframework.lang.Nullable ScopeContextCache scopeContextCache) {
        this.authzService = authzService;
        this.rolePermissionRepository = rolePermissionRepository;
        this.assignmentRepository = assignmentRepository;
        this.authzVersionService = authzVersionService;
        this.scopeContextCache = scopeContextCache;
    }

    /**
     * Write feature tuples for a user based on their combined role permissions.
     * Applies deny-wins semantics: if any role DENYs a permission, the user is blocked.
     */
    public void syncFeatureTuplesForUser(String userId, List<RolePermission> allPermissions) {
        syncFeatureTuplesForUser(userId, allPermissions, false);
    }

    public void syncFeatureTuplesForUser(String userId, List<RolePermission> allPermissions, boolean skipVersionIncrement) {
        Map<String, ResolvedGrant> effective = resolveEffectiveGrants(allPermissions);
        boolean anyWriteFailed = false;

        for (var entry : effective.entrySet()) {
            String compositeKey = entry.getKey();
            ResolvedGrant grant = entry.getValue();
            String[] parts = compositeKey.split(":", 2);
            PermissionType type = PermissionType.valueOf(parts[0]);
            String key = parts[1];

            TupleMapping mapping = toTupleMapping(type, grant.grantType());
            if (mapping == null) continue;

            try {
                authzService.writeTuple(userId, mapping.relation(), mapping.objectType(), key);

                // If DENY, also write the blocked relation
                if (grant.grantType() == GrantType.DENY) {
                    String blockedObjectType = type.name().toLowerCase();
                    authzService.writeTuple(userId, "blocked", blockedObjectType, key);
                }
            } catch (Exception e) {
                anyWriteFailed = true;
                log.warn("OpenFGA tuple write failed for user:{} {}:{} — {}", userId, mapping.relation(), key, e.getMessage());
            }
        }
        // P0 fail-closed: only bump version if ALL OpenFGA writes succeeded
        if (!skipVersionIncrement && !anyWriteFailed) {
            authzVersionService.incrementVersion();
            if (scopeContextCache != null) scopeContextCache.evictUser(userId);
        } else if (anyWriteFailed) {
            log.warn("Skipping version bump for user:{} — OpenFGA writes had failures", userId);
        }
    }

    /**
     * Delete all feature tuples for a user, then re-sync from current role assignments.
     */
    @Transactional(readOnly = true)
    public void refreshFeatureTuples(String userId) {
        refreshFeatureTuples(userId, false);
    }

    @Transactional(readOnly = true)
    public void refreshFeatureTuples(String userId, boolean skipVersionIncrement) {
        Long numericUserId = Long.parseLong(userId);
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(numericUserId);
        List<Long> roleIds = assignments.stream()
                .map(a -> a.getRole().getId())
                .distinct()
                .toList();

        if (roleIds.isEmpty()) {
            deleteAllFeatureTuples(userId);
            return;
        }

        List<RolePermission> allPermissions = rolePermissionRepository.findByRoleIdIn(roleIds);
        deleteAllFeatureTuples(userId);
        syncFeatureTuplesForUser(userId, allPermissions, skipVersionIncrement);
    }

    /**
     * When a role's permissions change, refresh tuples for ALL users assigned to that role.
     */
    @Transactional(readOnly = true)
    public void propagateRoleChange(Long roleId) {
        List<UserRoleAssignment> assignments = assignmentRepository.findByRoleIdAndActiveTrue(roleId);
        Set<Long> userIds = assignments.stream()
                .map(UserRoleAssignment::getUserId)
                .collect(Collectors.toSet());

        log.info("Propagating role {} change to {} users", roleId, userIds.size());

        for (Long numericUserId : userIds) {
            try {
                refreshFeatureTuples(String.valueOf(numericUserId), true);
            } catch (Exception e) {
                log.error("Failed to refresh tuples for user:{} after role:{} change", numericUserId, roleId, e);
            }
        }
        authzVersionService.incrementVersion();
        if (scopeContextCache != null) scopeContextCache.evictAll();
    }

    /**
     * Write data scope tuples for a user.
     */
    public void syncScopeTuples(String userId, List<Long> companyIds, List<Long> projectIds,
                                List<Long> warehouseIds, List<Long> branchIds) {
        syncScopeTuples(userId, companyIds, projectIds, warehouseIds, branchIds, false);
    }

    public void syncScopeTuples(String userId, List<Long> companyIds, List<Long> projectIds,
                                List<Long> warehouseIds, List<Long> branchIds, boolean skipVersionIncrement) {
        boolean anyFailed = false;
        anyFailed |= !writeScopeTuplesSafe(userId, "viewer", "company", companyIds);
        anyFailed |= !writeScopeTuplesSafe(userId, "viewer", "project", projectIds);
        anyFailed |= !writeScopeTuplesSafe(userId, "operator", "warehouse", warehouseIds);
        anyFailed |= !writeScopeTuplesSafe(userId, "member", "branch", branchIds);
        if (!skipVersionIncrement && !anyFailed) {
            authzVersionService.incrementVersion();
            if (scopeContextCache != null) scopeContextCache.evictUser(userId);
        } else if (anyFailed) {
            log.warn("Skipping version bump for user:{} — scope tuple writes had failures", userId);
        }
    }

    /**
     * Resolve effective grants from multiple role permissions using deny-wins semantics.
     * Key format: "PERMISSION_TYPE:permission_key"
     */
    public Map<String, ResolvedGrant> resolveEffectiveGrants(List<RolePermission> permissions) {
        Map<String, ResolvedGrant> effective = new LinkedHashMap<>();

        for (RolePermission rp : permissions) {
            if (rp.getPermissionType() == null || rp.getPermissionKey() == null || rp.getGrantType() == null) {
                continue;
            }

            String compositeKey = rp.getPermissionType().name() + ":" + rp.getPermissionKey();
            ResolvedGrant existing = effective.get(compositeKey);

            if (existing == null) {
                effective.put(compositeKey, new ResolvedGrant(rp.getGrantType(), rp.getRole().getName()));
            } else if (rp.getGrantType() == GrantType.DENY) {
                // DENY always wins
                effective.put(compositeKey, new ResolvedGrant(GrantType.DENY, rp.getRole().getName()));
            } else if (existing.grantType() != GrantType.DENY && isHigherGrant(rp.getGrantType(), existing.grantType())) {
                // Higher grant wins (MANAGE > VIEW, ALLOW > VIEW)
                effective.put(compositeKey, new ResolvedGrant(rp.getGrantType(), rp.getRole().getName()));
            }
        }

        return effective;
    }

    // --- Private helpers ---

    /** @return true if write succeeded, false on failure */
    private boolean writeScopeTuplesSafe(String userId, String relation, String objectType, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return true;
        var tuples = ids.stream()
                .map(id -> OpenFgaAuthzService.writeTupleKey(userId, relation, objectType, String.valueOf(id)))
                .toList();
        try {
            authzService.writeTuples(tuples);
            log.debug("OpenFGA batch scope write: {} tuples for user:{} {}:{}", tuples.size(), userId, relation, objectType);
            return true;
        } catch (Exception e) {
            log.warn("OpenFGA batch scope write failed for user:{} {}:{} ({} tuples) — {}",
                    userId, relation, objectType, tuples.size(), e.getMessage());
            return false;
        }
    }

    /**
     * Delete known feature tuples for a user before re-sync.
     * Uses batch deleteTuples for efficiency.
     */
    private void deleteAllFeatureTuples(String userId) {
        Long numericUserId = Long.parseLong(userId);
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(numericUserId);
        List<Long> roleIds = assignments.stream().map(a -> a.getRole().getId()).distinct().toList();
        if (roleIds.isEmpty()) return;

        List<RolePermission> allPerms = rolePermissionRepository.findByRoleIdIn(roleIds);
        var deleteTuples = new ArrayList<>(allPerms.stream()
                .filter(rp -> rp.getPermissionType() != null && rp.getPermissionKey() != null && rp.getGrantType() != null)
                .flatMap(rp -> {
                    TupleMapping mapping = toTupleMapping(rp.getPermissionType(), rp.getGrantType());
                    if (mapping == null) return java.util.stream.Stream.empty();
                    var items = new ArrayList<>(List.of(
                            OpenFgaAuthzService.deleteTupleKey(userId, mapping.relation(), mapping.objectType(), rp.getPermissionKey())
                    ));
                    if (rp.getGrantType() == GrantType.DENY) {
                        items.add(OpenFgaAuthzService.deleteTupleKey(userId, "blocked", rp.getPermissionType().name().toLowerCase(), rp.getPermissionKey()));
                    }
                    return items.stream();
                })
                .toList());

        if (deleteTuples.isEmpty()) return;
        try {
            authzService.deleteTuples(deleteTuples);
            log.debug("OpenFGA batch feature delete: {} tuples for user:{}", deleteTuples.size(), userId);
        } catch (Exception e) {
            log.debug("OpenFGA batch feature delete (partial no-op) for user:{} ({} tuples) — {}",
                    userId, deleteTuples.size(), e.getMessage());
        }
    }

    private List<String> getRelationsForType(PermissionType type) {
        return switch (type) {
            case MODULE -> List.of("can_manage", "can_view", "blocked");
            case ACTION -> List.of("allowed", "blocked");
            case REPORT -> List.of("can_view", "blocked");
        };
    }

    private TupleMapping toTupleMapping(PermissionType type, GrantType grant) {
        return switch (type) {
            case MODULE -> switch (grant) {
                case MANAGE -> new TupleMapping("can_manage", "module");
                case VIEW -> new TupleMapping("can_view", "module");
                case DENY -> new TupleMapping("blocked", "module");
                case ALLOW -> new TupleMapping("can_view", "module");
            };
            case ACTION -> switch (grant) {
                case ALLOW -> new TupleMapping("allowed", "action");
                case DENY -> new TupleMapping("blocked", "action");
                default -> null;
            };
            case REPORT -> switch (grant) {
                case ALLOW, VIEW -> new TupleMapping("can_view", "report");
                case DENY -> new TupleMapping("blocked", "report");
                default -> null;
            };
        };
    }

    private boolean isHigherGrant(GrantType candidate, GrantType existing) {
        return grantOrdinal(candidate) > grantOrdinal(existing);
    }

    private int grantOrdinal(GrantType grant) {
        return switch (grant) {
            case DENY -> -1;
            case VIEW -> 1;
            case ALLOW -> 2;
            case MANAGE -> 3;
        };
    }

    public record ResolvedGrant(GrantType grantType, String sourceRole) {}
    private record TupleMapping(String relation, String objectType) {}
}
