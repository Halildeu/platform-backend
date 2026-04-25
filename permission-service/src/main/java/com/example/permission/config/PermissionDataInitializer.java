package com.example.permission.config;

import com.example.permission.model.Permission;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.service.RolePermissionGranuleDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Order(10)
public class PermissionDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PermissionDataInitializer.class);

    private final PermissionRepository permissionRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    // P1-B: Simplified permissions (~35) — granular reports→groups, user duplicates→3-tier
    // Consultation CNS-20260410-001 (Claude + Codex consensus: kademeli 93→35)
    private static final Map<String, PermissionDefinition> DEFAULT_PERMISSIONS = Map.ofEntries(
            // --- Modules (7) ---
            Map.entry("VIEW_USERS", new PermissionDefinition("View users within the scope", "Kullanıcı Yönetimi")),
            Map.entry("MANAGE_USERS", new PermissionDefinition("Create or update users within the scope", "Kullanıcı Yönetimi")),
            Map.entry("VIEW_PURCHASE", new PermissionDefinition("View purchase module", "Satın Alma")),
            Map.entry("APPROVE_PURCHASE", new PermissionDefinition("Approve purchase orders", "Satın Alma")),
            Map.entry("VIEW_WAREHOUSE", new PermissionDefinition("View warehouse module", "Depo")),
            Map.entry("MANAGE_WAREHOUSE", new PermissionDefinition("Manage warehouse inventory", "Depo")),
            Map.entry("THEME_ADMIN", new PermissionDefinition("Manage global themes and critical theme tokens", "Tema Yönetimi")),
            Map.entry("VARIANTS_READ", new PermissionDefinition("View grid variants and presets", "Variant")),
            Map.entry("VARIANTS_WRITE", new PermissionDefinition("Create or update personal variants", "Variant")),
            Map.entry("MANAGE_GLOBAL_VARIANTS", new PermissionDefinition("Manage shared and global variants", "Variant")),
            // --- Access (2 — read + write) ---
            Map.entry("access-read", new PermissionDefinition("Read access management data", "Access")),
            Map.entry("access-write", new PermissionDefinition("Create/update/delete access management records", "Access")),
            // --- Audit (1) ---
            Map.entry("audit-read", new PermissionDefinition("Read audit events", "Audit")),
            // --- Company (2) ---
            Map.entry("company-read", new PermissionDefinition("Read company master data", "Company")),
            Map.entry("company-write", new PermissionDefinition("Create or update company master data", "Company")),
            // --- System governance (4) ---
            Map.entry("role-manage", new PermissionDefinition("Manage roles", "Sistem Yönetimi")),
            Map.entry("permission-manage", new PermissionDefinition("Manage permission catalog / role-permission matrix", "Sistem Yönetimi")),
            Map.entry("permission-scope-manage", new PermissionDefinition("Manage user-permission scopes", "Sistem Yönetimi")),
            Map.entry("system-configure", new PermissionDefinition("Configure global module settings", "Sistem Yönetimi")),
            // --- User 3-tier (3 — replaces 8 duplicates) ---
            Map.entry("user-read", new PermissionDefinition("Read/list users", "Kullanıcı Yönetimi")),
            Map.entry("user-write", new PermissionDefinition("Create/update users", "Kullanıcı Yönetimi")),
            Map.entry("user-admin", new PermissionDefinition("Delete/import/export users + role assignment", "Kullanıcı Yönetimi")),
            // --- Reporting core (3) ---
            Map.entry("REPORT_VIEW", new PermissionDefinition("View reports", "Raporlama")),
            Map.entry("REPORT_EXPORT", new PermissionDefinition("Export reports (CSV/Excel)", "Raporlama")),
            Map.entry("REPORT_MANAGE", new PermissionDefinition("Manage report definitions and settings", "Raporlama")),
            // --- Report groups (4 — replaces 20 granular) ---
            Map.entry("reports.HR_REPORTS", new PermissionDefinition("HR report group (personnel, salary, attendance, leave, payroll)", "reporting")),
            Map.entry("reports.FINANCE_REPORTS", new PermissionDefinition("Finance report group (bank, cash, invoices, cheque, accounts)", "reporting")),
            Map.entry("reports.SALES_REPORTS", new PermissionDefinition("Sales report group (sales summary, stock status)", "reporting")),
            Map.entry("reports.ANALYTICS_REPORTS", new PermissionDefinition("Analytics dashboards (HR, finance analytics)", "reporting")),
            // --- Scope markers (2 — not report groups, scope modifiers) ---
            Map.entry("scope.all-companies-hr", new PermissionDefinition("Bypass company filter for HR reports", "scope")),
            Map.entry("scope.all-companies-fin", new PermissionDefinition("Bypass company filter for finance reports", "scope"))
    );

    // P1-B: Simplified role→permission mapping using groups
    private static final Map<String, Set<String>> DEFAULT_ROLE_PERMISSIONS = Map.ofEntries(
            Map.entry("ADMIN", Set.of(
                    "VIEW_USERS", "MANAGE_USERS", "APPROVE_PURCHASE", "MANAGE_WAREHOUSE",
                    "VARIANTS_READ", "VARIANTS_WRITE", "MANAGE_GLOBAL_VARIANTS",
                    "access-read", "access-write",
                    "audit-read",
                    "company-read", "company-write",
                    "role-manage", "permission-manage", "permission-scope-manage", "system-configure",
                    "THEME_ADMIN",
                    "user-read", "user-write", "user-admin",
                    "REPORT_VIEW", "REPORT_EXPORT", "REPORT_MANAGE",
                    "reports.HR_REPORTS", "reports.FINANCE_REPORTS", "reports.SALES_REPORTS", "reports.ANALYTICS_REPORTS",
                    "scope.all-companies-hr", "scope.all-companies-fin"
            )),
            Map.entry("REPORT_VIEWER", Set.of("REPORT_VIEW")),
            Map.entry("REPORT_MANAGER", Set.of("REPORT_VIEW", "REPORT_EXPORT", "REPORT_MANAGE")),
            Map.entry("USER_MANAGE", Set.of("user-read", "user-write", "user-admin")),
            Map.entry("ROLE_MANAGE", Set.of("role-manage")),
            Map.entry("PERMISSION_MANAGE", Set.of("permission-manage", "permission-scope-manage")),
            Map.entry("SYSTEM_CONFIGURE", Set.of("system-configure")),
            Map.entry("AUDIT_READ", Set.of("audit-read")),
            Map.entry("USER_MANAGER", Set.of("VIEW_USERS", "MANAGE_USERS")),
            Map.entry("USER_VIEWER", Set.of("VIEW_USERS")),
            Map.entry("PURCHASE_MANAGER", Set.of("VIEW_USERS", "APPROVE_PURCHASE")),
            Map.entry("WAREHOUSE_OPERATOR", Set.of("VIEW_USERS", "MANAGE_WAREHOUSE")),
            // Finance roles — least-privilege preserved (Codex consensus)
            Map.entry("FINANCE_VIEWER", Set.of(
                    "REPORT_VIEW",
                    "reports.FINANCE_REPORTS", "reports.ANALYTICS_REPORTS"
            )),
            Map.entry("FINANCE_MANAGER", Set.of(
                    "REPORT_VIEW", "REPORT_EXPORT",
                    "reports.FINANCE_REPORTS", "reports.ANALYTICS_REPORTS",
                    "scope.all-companies-fin"
            ))
    );

    public PermissionDataInitializer(PermissionRepository permissionRepository,
                                     RoleRepository roleRepository,
                                     RolePermissionRepository rolePermissionRepository) {
        this.permissionRepository = permissionRepository;
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    private record PermissionDefinition(String description, String moduleName) { }

    @Override
    @Transactional
    public void run(String... args) {
        Map<String, Permission> existingPermissions = permissionRepository.findAll()
                .stream()
                .collect(Collectors.toMap(permission -> permission.getCode().toLowerCase(), permission -> permission));

        DEFAULT_PERMISSIONS.forEach((code, definition) -> {
            String normalizedKey = code.toLowerCase();
            existingPermissions.computeIfAbsent(normalizedKey, key -> {
                Permission permission = new Permission();
                permission.setCode(code);
                permission.setDescription(definition.description());
                permission.setModuleName(definition.moduleName());
                Permission saved = permissionRepository.save(permission);
                log.info("Created default permission {}", code);
                return saved;
            });
        });

        DEFAULT_PERMISSIONS.forEach((code, definition) -> {
            Permission permission = existingPermissions.get(code.toLowerCase());
            if (permission == null) {
                return;
            }
            boolean dirty = false;
            if (permission.getDescription() == null || permission.getDescription().isBlank()) {
                permission.setDescription(definition.description());
                dirty = true;
            }
            if (definition.moduleName() != null &&
                    (permission.getModuleName() == null || permission.getModuleName().isBlank())) {
                permission.setModuleName(definition.moduleName());
                dirty = true;
            }
            if (dirty) {
                permissionRepository.save(permission);
                log.info("Updated metadata for permission {}", permission.getCode());
            }
        });

        Map<String, Role> existingRoles = roleRepository.findAll()
                .stream()
                .collect(Collectors.toMap(role -> role.getName().toUpperCase(), role -> role));

        DEFAULT_ROLE_PERMISSIONS.forEach((roleName, permissions) -> {
            String normalizedRoleName = roleName.toUpperCase();
            Role role = existingRoles.computeIfAbsent(normalizedRoleName, key -> {
                Role newRole = new Role();
                newRole.setName(normalizedRoleName);
                newRole.setDescription("%s role".formatted(normalizedRoleName));
                Role savedRole = roleRepository.save(newRole);
                log.info("Created default role {}", normalizedRoleName);
                return savedRole;
            });

            Set<String> currentPermissionCodes = role.getRolePermissions()
                    .stream()
                    .map(rp -> rp.getPermission().getCode().toLowerCase())
                    .collect(Collectors.toSet());

            permissions.stream()
                    .map(String::toLowerCase)
                    .filter(permissionCode -> !currentPermissionCodes.contains(permissionCode))
                    .map(existingPermissions::get)
                    .forEach(permission -> {
                        RolePermission rolePermission = new RolePermission();
                        rolePermission.setRole(role);
                        RolePermissionGranuleDefaults.apply(rolePermission, permission);
                        rolePermissionRepository.save(rolePermission);
                        log.info("Linked permission {} to role {}", permission.getCode(), role.getName());
                    });
        });
    }
}
