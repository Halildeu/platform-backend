package com.example.permission.service;

import com.example.permission.dto.PermissionAssignRequest;
import com.example.permission.dto.PermissionAssignmentUpdateRequest;
import com.example.permission.dto.PermissionResponse;
import com.example.permission.model.PermissionAuditEvent;
import com.example.permission.model.Role;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);
    private static final String AUDIT_SERVICE_NAME = "permission-service";

    private final UserRoleAssignmentRepository assignmentRepository;
    private final RoleRepository roleRepository;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;
    private final TupleSyncService tupleSyncService;

    // Legacy permission-code → OpenFGA module-tuple mapping moved to
    // LegacyPermissionTupleMapper (AG-028 #1272, Codex Option B) so the legacy
    // WRITE path here and the granule-refresh spare-set in TupleSyncService
    // share one source and cannot drift.

    public PermissionService(UserRoleAssignmentRepository assignmentRepository,
                             RoleRepository roleRepository,
                             AuditEventService auditEventService,
                             ObjectMapper objectMapper,
                             @org.springframework.lang.Nullable TupleSyncService tupleSyncService) {
        this.assignmentRepository = assignmentRepository;
        this.roleRepository = roleRepository;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
        this.tupleSyncService = tupleSyncService;
    }

    // #1275 (Codex 019ea233): the fail-silent per-role legacy WRITE/DELETE path
    // syncTuplesToOpenFga(userId, role, write) is GONE. It swallowed genuine
    // OpenFGA write/delete failures (log.warn only) so the DB tx committed with no
    // tuple (split state), and its per-role delete had no spare-set (multi-role
    // over-delete). All legacy module:* tuple sync now flows through the fail-loud
    // aggregate TupleSyncService paths (refreshFeatureAndLegacyTuplesForUser /
    // refreshFeatureTuples), so PermissionService no longer touches OpenFgaAuthzService directly.

    @Transactional(readOnly = true)
    public boolean hasPermission(Long userId,
                                 Long companyId,
                                 Long projectId,
                                 Long warehouseId,
                                 String action) {
        String normalizedAction = action == null ? null : action.trim().toUpperCase(Locale.ROOT);
        if (normalizedAction == null || normalizedAction.isEmpty()) {
            return false;
        }

        List<UserRoleAssignment> assignments = new ArrayList<>(assignmentRepository.findActiveAssignments(userId, companyId, projectId, warehouseId));

        if (companyId != null) {
            List<UserRoleAssignment> globalAssignments = assignmentRepository.findActiveAssignments(userId, null, projectId, warehouseId);
            mergeAssignments(assignments, globalAssignments);
        }

        // CNS-20260416-001 B3 fix (parallel): legacy hasPermission() yolu için de
        // null-safe. Granule-only roller permission entity null → eski permission-code
        // check path'i skip (TupleSync OpenFGA tarafında bağımsız çalışır).
        return assignments.stream()
                .flatMap(assignment -> assignment.getRole().getRolePermissions().stream())
                .filter(rp -> rp.getPermission() != null)
                .map(rolePermission -> rolePermission.getPermission().getCode().toUpperCase(Locale.ROOT))
                .anyMatch(permissionCode -> permissionCode.equals(normalizedAction));
    }

    @Transactional
    public PermissionResponse assignRole(PermissionAssignRequest request) {
        Role role = roleRepository.findById(request.getRoleId())
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + request.getRoleId()));

        assignmentRepository.findActiveAssignment(
                        request.getUserId(),
                        request.getCompanyId(),
                        request.getRoleId(),
                        request.getProjectId(),
                        request.getWarehouseId())
                .ifPresent(existing -> {
                    throw new IllegalStateException("Active assignment already exists for the provided scope.");
                });

        UserRoleAssignment assignment = new UserRoleAssignment();
        assignment.setUserId(request.getUserId());
        assignment.setCompanyId(request.getCompanyId());
        assignment.setProjectId(request.getProjectId());
        assignment.setWarehouseId(request.getWarehouseId());
        assignment.setRole(role);
        assignment.setAssignedBy(request.getAssignedBy());
        assignment.setAssignedAt(Instant.now());
        assignment.setActive(true);

        UserRoleAssignment savedAssignment = assignmentRepository.save(assignment);

        String correlationId = UUID.randomUUID().toString();
        Map<String, Object> metadata = buildMetadata(request.getUserId(), role,
                request.getCompanyId(), request.getProjectId(), request.getWarehouseId());
        PermissionAuditEvent auditEvent = buildAuditEvent(
                "ASSIGN_ROLE",
                request.getAssignedBy(),
                "Role %s assigned to user %d (%s)".formatted(
                        role.getName(),
                        request.getUserId(),
                        formatScope(request.getCompanyId(), request.getProjectId(), request.getWarehouseId())),
                request.getUserId(),
                "INFO",
                "ROLE_ASSIGNED",
                correlationId,
                metadata,
                Map.of(),
                snapshotAssignment(savedAssignment)
        );
        PermissionAuditEvent savedEvent = auditEventService.recordEvent(auditEvent);

        // OpenFGA GAIN-reconcile (#1275 Option A, Codex 019ea233): a new active role.
        // refreshFeatureAndLegacyTuplesForUser writes the AGGREGATE legacy desired set
        // (the new role's module:* tuples, which the granule refresh does not write) AND
        // the granule desired set, both fail-loud, single version bump. This folds in
        // #1274 grant-add correctness (Codex 019e93bc): a member added AFTER the role's
        // granules exist gets the granule feature tuples immediately, instead of waiting
        // for the next RoleChangeEvent. Replaces the removed fail-silent syncTuplesToOpenFga.
        if (tupleSyncService != null) {
            tupleSyncService.refreshFeatureAndLegacyTuplesForUser(String.valueOf(request.getUserId()));
        }

        PermissionResponse response = toResponse(savedAssignment);
        if (savedEvent != null && savedEvent.getId() != null) {
            response.setAuditId(savedEvent.getId().toString());
        }
        return response;
    }

    @Transactional
    public PermissionResponse updateAssignment(PermissionAssignmentUpdateRequest request) {
        Role targetRole = resolveTargetRole(request);

        List<UserRoleAssignment> activeAssignments = assignmentRepository.findActiveAssignments(
                request.getUserId(),
                request.getCompanyId(),
                request.getProjectId(),
                request.getWarehouseId());

        Instant now = Instant.now();

        if (activeAssignments.isEmpty()) {
            PermissionAssignRequest assignRequest = new PermissionAssignRequest();
            assignRequest.setUserId(request.getUserId());
            assignRequest.setCompanyId(request.getCompanyId());
            assignRequest.setProjectId(request.getProjectId());
            assignRequest.setWarehouseId(request.getWarehouseId());
            assignRequest.setRoleId(targetRole.getId());
            assignRequest.setAssignedBy(request.getPerformedBy());
            return assignRole(assignRequest);
        }

        UserRoleAssignment primaryAssignment = activeAssignments.get(0);
        String correlationId = UUID.randomUUID().toString();

        String auditIdForResponse = null;
        if (!primaryAssignment.getRole().getId().equals(targetRole.getId())) {
            Map<String, Object> beforeSnapshot = snapshotAssignment(primaryAssignment);
            primaryAssignment.setRole(targetRole);
            primaryAssignment.setAssignedAt(now);
            primaryAssignment.setAssignedBy(request.getPerformedBy());
            primaryAssignment.setActive(true);
            primaryAssignment.setRevokedAt(null);
            primaryAssignment.setRevokedBy(null);
            UserRoleAssignment updatedPrimary = assignmentRepository.save(primaryAssignment);

            PermissionAuditEvent updateEvent = buildAuditEvent(
                    "UPDATE_ROLE",
                    request.getPerformedBy(),
                    "Role %s assigned to user %d (%s)".formatted(
                            targetRole.getName(),
                            request.getUserId(),
                            formatScope(request.getCompanyId(), request.getProjectId(), request.getWarehouseId())),
                    request.getUserId(),
                    "INFO",
                    "ROLE_UPDATED",
                    correlationId,
                    buildMetadata(request.getUserId(), targetRole,
                            request.getCompanyId(), request.getProjectId(), request.getWarehouseId()),
                    beforeSnapshot,
                    snapshotAssignment(updatedPrimary)
            );
            PermissionAuditEvent savedUpdate = auditEventService.recordEvent(updateEvent);
            if (savedUpdate != null && savedUpdate.getId() != null) {
                auditIdForResponse = savedUpdate.getId().toString();
            }
        }

        if (activeAssignments.size() > 1) {
            for (int i = 1; i < activeAssignments.size(); i++) {
                UserRoleAssignment extra = activeAssignments.get(i);
                Map<String, Object> beforeSnapshot = snapshotAssignment(extra);
                extra.setActive(false);
                extra.setRevokedAt(now);
                extra.setRevokedBy(request.getPerformedBy());
                UserRoleAssignment revokedAssignment = assignmentRepository.save(extra);

                PermissionAuditEvent revokeEvent = buildAuditEvent(
                        "REVOKE_ROLE",
                        request.getPerformedBy(),
                        "Assignment %d revoked during update".formatted(extra.getId()),
                        request.getUserId(),
                        "WARN",
                        "ROLE_REVOKED",
                        correlationId,
                        buildMetadata(request.getUserId(), extra.getRole(),
                                request.getCompanyId(), request.getProjectId(), request.getWarehouseId()),
                        beforeSnapshot,
                        snapshotAssignment(revokedAssignment)
                );
                auditEventService.recordEvent(revokeEvent);
            }
        }

        PermissionResponse response = toResponse(primaryAssignment);
        if (auditIdForResponse != null) {
            response.setAuditId(auditIdForResponse);
        }

        // OpenFGA GAIN-reconcile (#1275 Option A, Codex 019ea233): a role SWAP is a
        // GAIN path too — the swapped-in role may carry legacy FK permissions whose
        // module:* tuples the granule refresh does NOT write. The shared GAIN reconcile
        // writes the swapped-in role's legacy + granule tuples and deletes the
        // swapped-out role's stale tuples (spare-set), in-tx + fail-loud. Closes the
        // pre-fix gap where updateAssignment wrote granule but never the new legacy
        // module:* tuple (previously this called only refreshFeatureTuples).
        if (tupleSyncService != null) {
            tupleSyncService.refreshFeatureAndLegacyTuplesForUser(String.valueOf(request.getUserId()));
        }
        return response;
    }

    @Transactional
    public String revokeRole(Long assignmentId, Long performedBy) {
        UserRoleAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found: " + assignmentId));

        if (!assignment.isActive()) {
            return null;
        }

        Map<String, Object> beforeSnapshot = snapshotAssignment(assignment);
        assignment.setActive(false);
        assignment.setRevokedAt(Instant.now());
        assignment.setRevokedBy(performedBy);
        UserRoleAssignment revokedAssignment = assignmentRepository.save(assignment);

        PermissionAuditEvent auditEvent = buildAuditEvent(
                "REVOKE_ROLE",
                performedBy,
                "Assignment %d revoked".formatted(assignmentId),
                assignment.getUserId(),
                "WARN",
                "ROLE_REVOKED",
                UUID.randomUUID().toString(),
                buildMetadata(assignment.getUserId(), assignment.getRole(),
                        assignment.getCompanyId(), assignment.getProjectId(), assignment.getWarehouseId()),
                beforeSnapshot,
                snapshotAssignment(revokedAssignment)
        );
        PermissionAuditEvent saved = auditEventService.recordEvent(auditEvent);

        // OpenFGA REMOVE-reconcile (#1275 Option A, Codex 019ea233): a pure-removal
        // path. The fail-silent per-role legacy delete (syncTuplesToOpenFga ...,false)
        // is GONE — refreshFeatureTuples reconciles from the user's REMAINING active
        // roles so the revoked role's stale tuples (granule AND legacy module:*) are
        // deleted via the spare-set, while a tuple still justified by another active
        // role is PRESERVED (fixes the latent multi-role over-delete the old per-role
        // delete had). No positive legacy write is needed on removal. In-tx + fail-loud
        // → an FGA failure rolls back this revoke so a retry re-attempts atomically
        // (never a revoked-assignment-with-live-tuple split state). Central chokepoint
        // shared by DELETE /roles/{id}/members/{id} and DELETE /permissions/assignments/{id}.
        if (tupleSyncService != null) {
            tupleSyncService.refreshFeatureTuples(String.valueOf(assignment.getUserId()));
        }
        return saved != null && saved.getId() != null ? saved.getId().toString() : null;
    }

    @Transactional(readOnly = true)
    public List<PermissionResponse> getAssignments(Long userId,
                                                   Long companyId,
                                                   Long projectId,
                                                   Long warehouseId) {
        List<UserRoleAssignment> assignments;
        if (companyId == null) {
            assignments = assignmentRepository.findActiveAssignments(userId);
        } else {
            assignments = new ArrayList<>(assignmentRepository.findActiveAssignments(userId, companyId, projectId, warehouseId));
            List<UserRoleAssignment> globalAssignments = assignmentRepository.findActiveAssignments(userId, null, projectId, warehouseId);
            mergeAssignments(assignments, globalAssignments);
        }
        return assignments.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private PermissionAuditEvent buildAuditEvent(String eventType,
                                                 Long performedBy,
                                                 String details,
                                                 Long targetUserId,
                                                 String level,
                                                 String action,
                                                 String correlationId,
                                                 Map<String, Object> metadata,
                                                 Object beforeState,
                                                 Object afterState) {
        PermissionAuditEvent event = new PermissionAuditEvent();
        event.setEventType(eventType);
        event.setPerformedBy(performedBy);
        event.setDetails(details);
        event.setUserEmail(resolveUserIdentifier(targetUserId));
        event.setService(AUDIT_SERVICE_NAME);
        event.setLevel(level);
        event.setAction(action);
        event.setCorrelationId(correlationId != null ? correlationId : UUID.randomUUID().toString());
        event.setMetadata(writeJson(metadata));
        event.setBeforeState(writeJson(beforeState));
        event.setAfterState(writeJson(afterState));
        event.setOccurredAt(Instant.now());
        return event;
    }

    private Map<String, Object> snapshotAssignment(UserRoleAssignment assignment) {
        if (assignment == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("assignmentId", assignment.getId());
        snapshot.put("userId", assignment.getUserId());
        snapshot.put("active", assignment.isActive());
        snapshot.put("role", snapshotRole(assignment.getRole()));
        Map<String, Object> scope = buildScope(assignment.getCompanyId(), assignment.getProjectId(), assignment.getWarehouseId());
        if (!scope.isEmpty()) {
            snapshot.put("scope", scope);
        }
        if (assignment.getAssignedAt() != null) {
            snapshot.put("assignedAt", assignment.getAssignedAt().toString());
        }
        if (assignment.getAssignedBy() != null) {
            snapshot.put("assignedBy", assignment.getAssignedBy());
        }
        if (assignment.getRevokedAt() != null) {
            snapshot.put("revokedAt", assignment.getRevokedAt().toString());
        }
        if (assignment.getRevokedBy() != null) {
            snapshot.put("revokedBy", assignment.getRevokedBy());
        }
        return snapshot;
    }

    private Map<String, Object> snapshotRole(Role role) {
        if (role == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", role.getId());
        snapshot.put("name", role.getName());
        // STORY-0318/OI-03: granule-only rows carry permission=null. Split legacy
        // permission codes from granule triples to keep audit snapshot complete
        // without NPE (PermissionService.java B3 fix covers sync paths; this is
        // the audit sibling).
        var rolePermissions = role.getRolePermissions() == null ? java.util.List.<com.example.permission.model.RolePermission>of() : role.getRolePermissions();
        Set<String> permissions = rolePermissions.stream()
                .filter(rp -> rp.getPermission() != null)
                .map(rp -> rp.getPermission().getCode())
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        snapshot.put("permissions", permissions);
        List<Map<String, String>> granules = rolePermissions.stream()
                .filter(rp -> rp.getPermission() == null)
                .map(rp -> Map.of(
                        "type", rp.getPermissionType() != null ? rp.getPermissionType().name() : "",
                        "key", rp.getPermissionKey() != null ? rp.getPermissionKey() : "",
                        "grant", rp.getGrantType() != null ? rp.getGrantType().name() : ""))
                .toList();
        if (!granules.isEmpty()) {
            snapshot.put("granules", granules);
        }
        return snapshot;
    }

    private Map<String, Object> buildScope(Long companyId, Long projectId, Long warehouseId) {
        Map<String, Object> scope = new LinkedHashMap<>();
        if (companyId != null) {
            scope.put("companyId", companyId);
        }
        if (projectId != null) {
            scope.put("projectId", projectId);
        }
        if (warehouseId != null) {
            scope.put("warehouseId", warehouseId);
        }
        return scope;
    }

    private Map<String, Object> buildMetadata(Long userId,
                                              Role role,
                                              Long companyId,
                                              Long projectId,
                                              Long warehouseId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (userId != null) {
            metadata.put("userId", userId);
        }
        if (role != null) {
            metadata.put("roleId", role.getId());
            metadata.put("roleName", role.getName());
        }
        Map<String, Object> scope = buildScope(companyId, projectId, warehouseId);
        if (!scope.isEmpty()) {
            metadata.put("scope", scope);
        }
        return metadata;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        if (value instanceof Iterable<?> iterable && !iterable.iterator().hasNext()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise audit payload", e);
            return null;
        }
    }

    private String resolveUserIdentifier(Long userId) {
        if (userId == null) {
            return null;
        }
        return "user:%d".formatted(userId);
    }

    private void mergeAssignments(List<UserRoleAssignment> base, List<UserRoleAssignment> additional) {
        for (UserRoleAssignment candidate : additional) {
            Long candidateId = candidate.getId();
            boolean exists = candidateId != null && base.stream()
                    .map(UserRoleAssignment::getId)
                    .anyMatch(existingId -> existingId != null && existingId.equals(candidateId));
            if (!exists) {
                base.add(candidate);
            }
        }
    }

    private String formatScope(Long companyId, Long projectId, Long warehouseId) {
        StringBuilder scope = new StringBuilder();
        if (companyId != null) {
            scope.append("company ").append(companyId);
        }
        if (projectId != null) {
            if (scope.length() > 0) {
                scope.append(", ");
            }
            scope.append("project ").append(projectId);
        }
        if (warehouseId != null) {
            if (scope.length() > 0) {
                scope.append(", ");
            }
            scope.append("warehouse ").append(warehouseId);
        }
        return scope.length() > 0 ? scope.toString() : "global scope";
    }

    private PermissionResponse toResponse(UserRoleAssignment assignment) {
        // STORY-0318/OI-03: granule-only rows (permission=null) are filtered out
        // of the legacy permission-code map. Legacy assignRole/updateAssignment
        // response surface preserved for permission-registry roles; granule-only
        // roles expose their semantics via /authz/me + RolePolicyDto instead.
        Map<String, String> permissionModules = assignment.getRole().getRolePermissions().stream()
                .filter(rp -> rp.getPermission() != null)
                .collect(Collectors.toMap(
                        rolePermission -> rolePermission.getPermission().getCode(),
                        rolePermission -> {
                            String moduleName = rolePermission.getPermission().getModuleName();
                            return moduleName != null ? moduleName : "";
                        },
                        (first, second) -> first,
                        java.util.LinkedHashMap::new));

        Set<String> permissionCodes = permissionModules.keySet();

        String moduleLabel = permissionModules.values().stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElseGet(() -> deriveModuleLabelFromRole(assignment.getRole().getName()));

        String moduleKey = sanitizeModuleKey(moduleLabel);
        if (moduleKey.isBlank() && assignment.getRole().getName() != null) {
            moduleKey = sanitizeModuleKey(assignment.getRole().getName());
        }
        if (moduleLabel == null || moduleLabel.isBlank()) {
            moduleLabel = moduleKey.isBlank() ? "Genel Modül" : moduleKey.replace('_', ' ');
        }

        PermissionResponse response = new PermissionResponse();
        response.setAssignmentId(assignment.getId());
        response.setUserId(assignment.getUserId());
        response.setCompanyId(assignment.getCompanyId());
        response.setProjectId(assignment.getProjectId());
        response.setWarehouseId(assignment.getWarehouseId());
        response.setRoleId(assignment.getRole().getId());
        response.setRoleName(assignment.getRole().getName());
        response.setModuleKey(moduleKey);
        response.setModuleLabel(moduleLabel);
        response.setActive(assignment.isActive());
        response.setAssignedAt(assignment.getAssignedAt());
        response.setRevokedAt(assignment.getRevokedAt());
        response.setPermissions(permissionCodes);
        response.setPermissionModules(permissionModules);
        return response;
    }

    private String deriveModuleLabelFromRole(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return "Genel Modül";
        }
        String normalized = roleName.trim().replace('_', ' ').toLowerCase(Locale.ROOT);
        return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
    }

    private String sanitizeModuleKey(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^A-Z0-9]+", "_");
        normalized = normalized.replaceAll("_+", "_");
        if (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private Role resolveTargetRole(PermissionAssignmentUpdateRequest request) {
        if (request.getRoleId() != null) {
            return roleRepository.findById(request.getRoleId())
                    .orElseThrow(() -> new IllegalArgumentException("Role not found: " + request.getRoleId()));
        }

        if (request.getRoleName() != null && !request.getRoleName().isBlank()) {
            return roleRepository.findByNameIgnoreCase(request.getRoleName().trim())
                    .orElseThrow(() -> new IllegalArgumentException("Role not found: " + request.getRoleName()));
        }

        throw new IllegalArgumentException("roleId or roleName must be provided");
    }
}
