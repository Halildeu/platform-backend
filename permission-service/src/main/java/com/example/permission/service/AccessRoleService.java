package com.example.permission.service;

import com.example.permission.dto.access.AccessModulePolicyDto;
import com.example.permission.dto.access.AccessRoleDto;
import com.example.permission.dto.v1.BulkPermissionsResponseDto;
import com.example.permission.dto.v1.PermissionDtoMapper;
import com.example.permission.dto.v1.RoleCloneResponseDto;
import com.example.permission.dto.v1.RoleDto;
import com.example.permission.dto.v1.RolePermissionsUpdateResponseDto;
import com.example.permission.model.Permission;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AccessRoleService {

    private static final Logger log = LoggerFactory.getLogger(AccessRoleService.class);

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final AuditEventService auditEventService;
    private final OpenFgaAuthzService authzService;

    public AccessRoleService(RoleRepository roleRepository,
                             RolePermissionRepository rolePermissionRepository,
                             PermissionRepository permissionRepository,
                             UserRoleAssignmentRepository assignmentRepository,
                             AuditEventService auditEventService,
                             OpenFgaAuthzService authzService) {
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.permissionRepository = permissionRepository;
        this.assignmentRepository = assignmentRepository;
        this.auditEventService = auditEventService;
        this.authzService = authzService;
    }

    @Transactional(readOnly = true)
    public List<AccessRoleDto> listRoles() {
        List<Role> roles = roleRepository.findAll();
        return roles.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public AccessRoleDto getRole(Long roleId) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
        return toDto(role);
    }

    @Transactional
    public RoleDto createRole(String name, String description, Long performedBy) {
        if (roleRepository.findByNameIgnoreCase(name).isPresent()) {
            throw new IllegalArgumentException("Role name already exists: " + name);
        }
        Role role = new Role();
        role.setName(name);
        role.setDescription(description);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        Role saved = roleRepository.save(role);

        auditEventService.recordEvent(
                auditEventService.buildEvent(
                        "CREATE_ROLE",
                        performedBy,
                        "Role created: %s".formatted(saved.getName()),
                        null,
                        "INFO",
                        "ROLE_CREATED",
                        Map.of("roleId", saved.getId(), "roleName", saved.getName()),
                        null,
                        Map.of("role", saved.getName())
                )
        );

        return PermissionDtoMapper.toRoleDto(toDto(saved));
    }

    @Transactional
    public void deleteRole(Long roleId, Long performedBy) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));

        // Capture before state for audit
        Map<String, Object> beforeState = Map.of(
                "role", role.getName(),
                "permissionCount", role.getRolePermissions().size(),
                "memberCount", assignmentRepository.countByRoleAndActiveTrue(role)
        );

        // Deactivate all assignments
        assignmentRepository.findByRoleAndActiveTrue(role).forEach(assignment -> {
            assignment.setActive(false);
            assignmentRepository.save(assignment);
        });

        // Delete role permissions and role
        rolePermissionRepository.deleteAll(role.getRolePermissions());
        roleRepository.delete(role);

        auditEventService.recordEvent(
                auditEventService.buildEvent(
                        "DELETE_ROLE",
                        performedBy,
                        "Role deleted: %s".formatted(role.getName()),
                        null,
                        "WARN",
                        "ROLE_DELETED",
                        Map.of("roleId", roleId, "roleName", role.getName()),
                        beforeState,
                        null
                )
        );
    }

    @Transactional
    public RoleCloneResponseDto cloneRole(Long sourceRoleId, String name, String description, Long performedBy) {
        Role source = roleRepository.findById(sourceRoleId)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + sourceRoleId));

        Role clone = new Role();
        clone.setName(name);
        clone.setDescription(description != null ? description : source.getDescription());
        clone.setCreatedAt(Instant.now());
        clone.setUpdatedAt(Instant.now());
        Role saved = roleRepository.save(clone);

        // Permissions copy
        for (RolePermission rp : source.getRolePermissions()) {
            RolePermission nrp = new RolePermission();
            nrp.setRole(saved);
            nrp.setPermission(rp.getPermission());
            rolePermissionRepository.save(nrp);
        }

        // Audit event
        var auditEvent = auditEventService.recordEvent(
                auditEventService.buildEvent(
                        "CLONE_ROLE",
                        performedBy,
                        "Role %s cloned to %s".formatted(source.getName(), saved.getName()),
                        null,
                        "INFO",
                        "ROLE_CLONED",
                        Map.of("sourceRoleId", source.getId(), "targetRoleId", saved.getId()),
                        Map.of("role", source.getName()),
                        Map.of("role", saved.getName())
                )
        );

        AccessRoleDto accessRoleDto = toDto(saved);
        RoleDto dto = PermissionDtoMapper.toRoleDto(accessRoleDto);
        String auditId = auditEvent != null && auditEvent.getId() != null ? auditEvent.getId().toString() : null;
        return new RoleCloneResponseDto(dto, auditId);
    }

    @Transactional
    public BulkPermissionsResponseDto bulkUpdateModuleLevel(List<Long> roleIds, String moduleKey, String moduleLabel, String level, Long performedBy) {
        if (roleIds == null || roleIds.isEmpty()) {
            return new BulkPermissionsResponseDto(List.of(), null);
        }
        Set<Long> updated = new LinkedHashSet<>();

        for (Long roleId : roleIds) {
            roleRepository.findById(roleId).ifPresent(role -> {
                boolean changed = applyLevelForModule(role, moduleKey, level);
                if (changed) {
                    role.setUpdatedAt(Instant.now());
                    roleRepository.save(role);
                    updated.add(role.getId());
                }
            });
        }

        var audit = auditEventService.recordEvent(
                auditEventService.buildEvent(
                        "BULK_PERMISSION",
                        performedBy,
                        "Bulk permission level %s applied for module %s".formatted(level, moduleKey),
                        null,
                        "INFO",
                        "PERMISSION_BULK",
                        Map.of("roleIds", roleIds, "moduleKey", moduleKey, "level", level),
                        null,
                        null
                )
        );

        String auditId = audit != null && audit.getId() != null ? audit.getId().toString() : null;
        return new BulkPermissionsResponseDto(new ArrayList<>(updated), auditId);
    }

    @Transactional
    public RolePermissionsUpdateResponseDto updateRolePermissions(Long roleId, List<String> permissionIds, Long performedBy) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));

        LinkedHashMap<Long, Permission> targetPermissions = resolvePermissions(permissionIds);
        Set<Long> currentPermissionIds = role.getRolePermissions().stream()
                .map(RolePermission::getPermission)
                .filter(Objects::nonNull)
                .map(Permission::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> targetPermissionIds = new LinkedHashSet<>(targetPermissions.keySet());

        List<RolePermission> toRemove = role.getRolePermissions().stream()
                .filter(rolePermission -> {
                    Permission permission = rolePermission.getPermission();
                    Long permissionId = permission != null ? permission.getId() : null;
                    return permissionId != null && !targetPermissionIds.contains(permissionId);
                })
                .toList();
        if (!toRemove.isEmpty()) {
            rolePermissionRepository.deleteAll(toRemove);
            role.getRolePermissions().removeIf(toRemove::contains);
        }

        for (Permission permission : targetPermissions.values()) {
            Long permissionId = permission.getId();
            if (permissionId == null || currentPermissionIds.contains(permissionId)) {
                continue;
            }
            RolePermission rolePermission = new RolePermission();
            rolePermission.setRole(role);
            rolePermission.setPermission(permission);
            rolePermissionRepository.save(rolePermission);
            role.getRolePermissions().add(rolePermission);
        }

        role.setUpdatedAt(Instant.now());
        roleRepository.save(role);

        List<String> beforePermissions = currentPermissionIds.stream()
                .sorted()
                .map(String::valueOf)
                .toList();
        List<String> afterPermissions = targetPermissionIds.stream()
                .map(String::valueOf)
                .toList();

        var audit = auditEventService.recordEvent(
                auditEventService.buildEvent(
                        "UPDATE_ROLE_PERMISSIONS",
                        performedBy,
                        "Role permissions updated for role %s".formatted(role.getName()),
                        null,
                        "INFO",
                        "ROLE_PERMISSIONS_UPDATED",
                        Map.of("roleId", role.getId(), "permissionCount", afterPermissions.size()),
                        Map.of("permissionIds", beforePermissions),
                        Map.of("permissionIds", afterPermissions)
                )
        );

        String auditId = audit != null && audit.getId() != null ? audit.getId().toString() : null;
        return new RolePermissionsUpdateResponseDto(true, auditId);
    }

    /**
     * @deprecated STORY-0318: Use PUT /v1/roles/{id}/granules with 5-granule format instead.
     * This hardcoded mapping will be removed after all consumers migrate.
     */
    private boolean applyLevelForModule(Role role, String moduleKey, String level) {
        // Şimdilik sadece USER_MANAGEMENT için deterministik mapping
        List<String> addPerms = new ArrayList<>();
        List<String> removePerms = new ArrayList<>();

        if ("USER_MANAGEMENT".equalsIgnoreCase(moduleKey)) {
            // Temel eşleme: VIEW_USERS / MANAGE_USERS
            switch (level == null ? "" : level.toUpperCase()) {
                case "NONE" -> {
                    removePerms.addAll(List.of("VIEW_USERS", "MANAGE_USERS"));
                }
                case "VIEW" -> {
                    addPerms.add("VIEW_USERS");
                    removePerms.add("MANAGE_USERS");
                }
                case "EDIT", "MANAGE" -> {
                    addPerms.add("MANAGE_USERS");
                    addPerms.add("VIEW_USERS");
                }
                default -> {}
            }
        } else if ("PURCHASE".equalsIgnoreCase(moduleKey)) {
            // Satın Alma: VIEW -> VIEW_PURCHASE, MANAGE -> APPROVE_PURCHASE
            switch (level == null ? "" : level.toUpperCase()) {
                case "NONE" -> removePerms.addAll(List.of("VIEW_PURCHASE", "APPROVE_PURCHASE"));
                case "VIEW" -> { addPerms.add("VIEW_PURCHASE"); removePerms.add("APPROVE_PURCHASE"); }
                case "MANAGE", "EDIT" -> { addPerms.add("APPROVE_PURCHASE"); addPerms.add("VIEW_PURCHASE"); }
                default -> {}
            }
        } else if ("WAREHOUSE".equalsIgnoreCase(moduleKey)) {
            // Depo: VIEW -> VIEW_WAREHOUSE, MANAGE -> MANAGE_WAREHOUSE
            switch (level == null ? "" : level.toUpperCase()) {
                case "NONE" -> removePerms.addAll(List.of("VIEW_WAREHOUSE", "MANAGE_WAREHOUSE"));
                case "VIEW" -> { addPerms.add("VIEW_WAREHOUSE"); removePerms.add("MANAGE_WAREHOUSE"); }
                case "MANAGE", "EDIT" -> { addPerms.add("MANAGE_WAREHOUSE"); addPerms.add("VIEW_WAREHOUSE"); }
                default -> {}
            }
        } else {
            // Diğer modüller: şimdilik değişiklik yok
            return false;
        }

        boolean changed = false;
        Map<String, Permission> byCode = permissionRepository.findAll().stream()
                .collect(Collectors.toMap(p -> p.getCode().toUpperCase(), p -> p));

        if (!removePerms.isEmpty()) {
            Set<String> removeSet = removePerms.stream().map(String::toUpperCase).collect(Collectors.toSet());
            List<RolePermission> toRemove = role.getRolePermissions().stream()
                    .filter(rp -> removeSet.contains(rp.getPermission().getCode().toUpperCase()))
                    .toList();
            if (!toRemove.isEmpty()) {
                rolePermissionRepository.deleteAll(toRemove);
                changed = true;
            }
        }

        for (String code : addPerms) {
            Permission p = byCode.get(code.toUpperCase());
            if (p == null) continue;
            boolean exists = role.getRolePermissions().stream()
                    .anyMatch(rp -> rp.getPermission().getCode().equalsIgnoreCase(p.getCode()));
            if (!exists) {
                RolePermission rp = new RolePermission();
                rp.setRole(role);
                rp.setPermission(p);
                rolePermissionRepository.save(rp);
                changed = true;
            }
        }

        return changed;
    }

    private AccessRoleDto toDto(Role role) {
        long memberCount = assignmentRepository.countByRoleAndActiveTrue(role);

        Map<String, List<RolePermission>> byModule = new LinkedHashMap<>();
        for (RolePermission rp : role.getRolePermissions()) {
            String module = rp.getPermission().getModuleName();
            if (module == null) module = "GENERIC";
            byModule.computeIfAbsent(module, k -> new ArrayList<>()).add(rp);
        }

        List<AccessModulePolicyDto> policies = byModule.entrySet().stream().map(entry -> {
            String defaultLabel = entry.getKey();
            var identity = deriveModuleIdentity(entry.getValue(), defaultLabel);
            String moduleKey = identity[0];
            String moduleLabel = identity[1];
            String level = deriveLevel(entry.getValue());
            return new AccessModulePolicyDto(
                    moduleKey,
                    moduleLabel,
                    level,
                    DateTimeFormatter.ISO_INSTANT.format(Optional.ofNullable(role.getUpdatedAt()).orElse(role.getCreatedAt() != null ? role.getCreatedAt() : Instant.now())),
                    "system"
            );
        }).toList();

        List<String> permissionIds = role.getRolePermissions().stream()
                .map(RolePermission::getPermission)
                .filter(Objects::nonNull)
                .map(Permission::getId)
                .filter(Objects::nonNull)
                .sorted()
                .map(String::valueOf)
                .toList();

        return new AccessRoleDto(
                role.getId(),
                role.getName(),
                role.getDescription(),
                memberCount,
                false,
                DateTimeFormatter.ISO_INSTANT.format(Optional.ofNullable(role.getUpdatedAt()).orElse(role.getCreatedAt() != null ? role.getCreatedAt() : Instant.now())),
                "system",
                policies,
                permissionIds
        );
    }

    private LinkedHashMap<Long, Permission> resolvePermissions(List<String> permissionIds) {
        LinkedHashMap<Long, Permission> resolved = new LinkedHashMap<>();
        if (permissionIds == null || permissionIds.isEmpty()) {
            return resolved;
        }

        List<String> missing = new ArrayList<>();
        for (String rawIdentifier : permissionIds) {
            String identifier = rawIdentifier == null ? "" : rawIdentifier.trim();
            if (identifier.isEmpty()) {
                continue;
            }

            Optional<Permission> permission = Optional.empty();
            if (identifier.chars().allMatch(Character::isDigit)) {
                permission = permissionRepository.findById(Long.parseLong(identifier));
            }
            if (permission.isEmpty()) {
                permission = permissionRepository.findByCodeIgnoreCase(identifier);
            }
            if (permission.isPresent() && permission.get().getId() != null) {
                resolved.putIfAbsent(permission.get().getId(), permission.get());
            } else {
                missing.add(identifier);
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Permissions not found: " + String.join(", ", missing));
        }
        return resolved;
    }

    private String normalizeModuleKey(String moduleLabel) {
        if (moduleLabel == null) return "GENERIC";
        return moduleLabel.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private String deriveLevel(List<RolePermission> rps) {
        Set<String> codes = rps.stream().map(rp -> rp.getPermission().getCode().toUpperCase()).collect(Collectors.toSet());
        if (codes.contains("MANAGE_USERS")) return "MANAGE";
        if (codes.contains("VIEW_USERS")) return "VIEW";
        // Varsayılan: en az bir permission varsa MANAGE sayalım
        return codes.isEmpty() ? "NONE" : "MANAGE";
    }

    private String[] deriveModuleIdentity(List<RolePermission> rps, String fallbackLabel) {
        Set<String> codes = rps.stream().map(rp -> rp.getPermission().getCode().toUpperCase()).collect(Collectors.toSet());
        if (codes.stream().anyMatch(code -> code.contains("USERS"))) {
            return new String[]{"USER_MANAGEMENT", "Kullanıcı Yönetimi"};
        }
        String label = fallbackLabel != null && !fallbackLabel.isBlank() ? fallbackLabel : "Genel Modül";
        return new String[]{normalizeModuleKey(label), label};
    }
}
