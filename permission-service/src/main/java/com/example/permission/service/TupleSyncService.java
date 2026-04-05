package com.example.permission.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
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
public class TupleSyncService {

    private static final Logger log = LoggerFactory.getLogger(TupleSyncService.class);

    private final OpenFgaAuthzService authzService;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRoleAssignmentRepository assignmentRepository;

    public TupleSyncService(OpenFgaAuthzService authzService,
                            RolePermissionRepository rolePermissionRepository,
                            UserRoleAssignmentRepository assignmentRepository) {
        this.authzService = authzService;
        this.rolePermissionRepository = rolePermissionRepository;
        this.assignmentRepository = assignmentRepository;
    }

    /**
     * Write feature tuples for a user based on their combined role permissions.
     * Applies deny-wins semantics: if any role DENYs a permission, the user is blocked.
     */
    public void syncFeatureTuplesForUser(String userId, List<RolePermission> allPermissions) {
        Map<String, ResolvedGrant> effective = resolveEffectiveGrants(allPermissions);

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
                log.warn("OpenFGA tuple write failed for user:{} {}:{} — {}", userId, mapping.relation(), key, e.getMessage());
            }
        }
    }

    /**
     * Delete all feature tuples for a user, then re-sync from current role assignments.
     */
    @Transactional(readOnly = true)
    public void refreshFeatureTuples(String userId) {
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
        syncFeatureTuplesForUser(userId, allPermissions);
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
                refreshFeatureTuples(String.valueOf(numericUserId));
            } catch (Exception e) {
                log.error("Failed to refresh tuples for user:{} after role:{} change", numericUserId, roleId, e);
            }
        }
    }

    /**
     * Write data scope tuples for a user.
     */
    public void syncScopeTuples(String userId, List<Long> companyIds, List<Long> projectIds,
                                List<Long> warehouseIds, List<Long> branchIds) {
        writeScopeTuples(userId, "viewer", "company", companyIds);
        writeScopeTuples(userId, "viewer", "project", projectIds);
        writeScopeTuples(userId, "operator", "warehouse", warehouseIds);
        writeScopeTuples(userId, "member", "branch", branchIds);
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

    private void writeScopeTuples(String userId, String relation, String objectType, List<Long> ids) {
        if (ids == null) return;
        for (Long id : ids) {
            try {
                authzService.writeTuple(userId, relation, objectType, String.valueOf(id));
            } catch (Exception e) {
                log.warn("OpenFGA scope tuple write failed for user:{} {}:{}:{} — {}",
                        userId, relation, objectType, id, e.getMessage());
            }
        }
    }

    private void deleteAllFeatureTuples(String userId) {
        for (PermissionType type : PermissionType.values()) {
            String objectType = type.name().toLowerCase();
            for (String relation : getRelationsForType(type)) {
                try {
                    authzService.deleteTuplesForUser(userId, relation, objectType);
                } catch (Exception e) {
                    log.debug("OpenFGA tuple delete (no-op if empty) for user:{} {}:{} — {}",
                            userId, relation, objectType, e.getMessage());
                }
            }
        }
    }

    private List<String> getRelationsForType(PermissionType type) {
        return switch (type) {
            case MODULE -> List.of("can_manage", "can_view", "blocked");
            case ACTION -> List.of("allowed", "blocked");
            case REPORT -> List.of("can_view", "blocked");
            case PAGE -> List.of("can_access", "blocked");
            case FIELD -> List.of("can_view", "blocked");
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
            case PAGE -> switch (grant) {
                case ALLOW -> new TupleMapping("can_access", "page");
                case DENY -> new TupleMapping("blocked", "page");
                default -> null;
            };
            case FIELD -> switch (grant) {
                case ALLOW, VIEW -> new TupleMapping("can_view", "field");
                case DENY -> new TupleMapping("blocked", "field");
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
