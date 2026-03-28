package com.example.permission.config;

import com.example.permission.model.Permission;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
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

    private static final Map<String, PermissionDefinition> DEFAULT_PERMISSIONS = Map.ofEntries(
            Map.entry("VIEW_USERS", new PermissionDefinition("View users within the scope", "Kullanıcı Yönetimi")),
            Map.entry("MANAGE_USERS", new PermissionDefinition("Create or update users within the scope", "Kullanıcı Yönetimi")),
            Map.entry("VIEW_PURCHASE", new PermissionDefinition("View purchase module", "Satın Alma")),
            Map.entry("APPROVE_PURCHASE", new PermissionDefinition("Approve purchase orders", "Satın Alma")),
            Map.entry("VIEW_WAREHOUSE", new PermissionDefinition("View warehouse module", "Depo")),
            Map.entry("MANAGE_WAREHOUSE", new PermissionDefinition("Manage warehouse inventory", "Depo")),
            // Access service (lowercase, hyphenated)
            Map.entry("access-read", new PermissionDefinition("Read access management data", "Access")),
            Map.entry("access-create", new PermissionDefinition("Create access management records", "Access")),
            Map.entry("access-update", new PermissionDefinition("Update access management records", "Access")),
            Map.entry("access-delete", new PermissionDefinition("Delete access management records", "Access")),
            // Audit service (lowercase, hyphenated)
            Map.entry("audit-read", new PermissionDefinition("Read audit events", "Audit")),
            Map.entry("audit-create", new PermissionDefinition("Create audit entries", "Audit")),
            Map.entry("audit-update", new PermissionDefinition("Update audit entries", "Audit")),
            Map.entry("audit-delete", new PermissionDefinition("Delete audit entries", "Audit")),
            // Core data / company master
            Map.entry("company-read", new PermissionDefinition("Read company master data", "Company")),
            Map.entry("company-write", new PermissionDefinition("Create or update company master data", "Company")),
            // System / governance
            Map.entry("role-manage", new PermissionDefinition("Manage roles", "Sistem Yönetimi")),
            Map.entry("permission-manage", new PermissionDefinition("Manage permission catalog / role-permission matrix", "Sistem Yönetimi")),
            Map.entry("permission-scope-manage", new PermissionDefinition("Manage user-permission scopes", "Sistem Yönetimi")),
            Map.entry("system-configure", new PermissionDefinition("Configure global module settings", "Sistem Yönetimi")),
            // Theme / personalization
            Map.entry("THEME_ADMIN", new PermissionDefinition("Manage global themes and critical theme tokens", "Tema Yönetimi")),
            // Reporting — core
            Map.entry("REPORT_VIEW", new PermissionDefinition("View reports", "Raporlama")),
            Map.entry("REPORT_EXPORT", new PermissionDefinition("Export reports (CSV/Excel)", "Raporlama")),
            Map.entry("REPORT_MANAGE", new PermissionDefinition("Manage report definitions and settings", "Raporlama")),
            // Reporting — HR reports
            Map.entry("reports.hr-personel-listesi.view", new PermissionDefinition("View HR personnel list report", "reporting")),
            Map.entry("reports.hr-maas-raporu.view", new PermissionDefinition("View HR salary report", "reporting")),
            Map.entry("reports.hr-giris-cikis.view", new PermissionDefinition("View HR hire/exit report", "reporting")),
            Map.entry("reports.hr-puantaj.view", new PermissionDefinition("View HR attendance report", "reporting")),
            Map.entry("reports.hr-maas-gecmisi.view", new PermissionDefinition("View HR salary history report", "reporting")),
            Map.entry("reports.hr-egitim-katilim.view", new PermissionDefinition("View HR training participation report", "reporting")),
            Map.entry("reports.hr-izin-raporu.view", new PermissionDefinition("View HR leave report", "reporting")),
            Map.entry("reports.hr-bordro-detay.view", new PermissionDefinition("View HR payroll detail report", "reporting")),
            Map.entry("reports.hr.all-companies", new PermissionDefinition("Bypass company filter for HR reports", "report")),
            // Reporting — sales reports
            Map.entry("reports.satis-ozet.view", new PermissionDefinition("View sales summary report", "reporting")),
            Map.entry("reports.stok-durum.view", new PermissionDefinition("View stock status report", "reporting")),
            // Reporting — finance reports
            Map.entry("reports.fin-banka-hareketleri.view", new PermissionDefinition("View bank transactions report", "reporting")),
            Map.entry("reports.fin-kasa-hareketleri.view", new PermissionDefinition("View cash desk transactions report", "reporting")),
            Map.entry("reports.fin-faturalar.view", new PermissionDefinition("View invoices report", "reporting")),
            Map.entry("reports.fin-cek-senet.view", new PermissionDefinition("View cheque/bond tracking report", "reporting")),
            Map.entry("reports.fin-cari-hareketler.view", new PermissionDefinition("View current account transactions report", "reporting")),
            Map.entry("reports.fin.all-companies", new PermissionDefinition("Bypass company filter for finance reports", "reporting")),
            // Reporting — finance dashboard
            Map.entry("dashboards.fin-analytics.view", new PermissionDefinition("View finance analytics dashboard", "reporting")),
            // Reporting — HR dashboards
            Map.entry("dashboards.hr-analytics.view", new PermissionDefinition("View HR analytics dashboard", "reporting")),
            Map.entry("dashboards.hr-finansal.view", new PermissionDefinition("View HR financial dashboard", "reporting")),
            // User export/import
            Map.entry("user-read", new PermissionDefinition("Read users", "Kullanıcı Yönetimi")),
            Map.entry("user-create", new PermissionDefinition("Create users", "Kullanıcı Yönetimi")),
            Map.entry("user-update", new PermissionDefinition("Update users", "Kullanıcı Yönetimi")),
            Map.entry("user-delete", new PermissionDefinition("Delete users", "Kullanıcı Yönetimi")),
            Map.entry("user-export", new PermissionDefinition("Export users (CSV/Excel)", "Kullanıcı Yönetimi")),
            Map.entry("user-import", new PermissionDefinition("Import users (bulk)", "Kullanıcı Yönetimi"))
    );

    private static final Map<String, Set<String>> DEFAULT_ROLE_PERMISSIONS = Map.ofEntries(
            Map.entry("ADMIN", Set.of(
                    "VIEW_USERS", "MANAGE_USERS", "APPROVE_PURCHASE", "MANAGE_WAREHOUSE",
                    "access-read", "access-create", "access-update", "access-delete",
                    "audit-read", "audit-create", "audit-update", "audit-delete",
                    "company-read", "company-write",
                    "role-manage", "permission-manage", "permission-scope-manage", "system-configure",
                    "THEME_ADMIN",
                    "user-read", "user-create", "user-update", "user-delete",
                    "user-export", "user-import",
                    "REPORT_VIEW", "REPORT_EXPORT", "REPORT_MANAGE",
                    // HR reports + dashboards
                    "reports.hr-personel-listesi.view", "reports.hr-maas-raporu.view",
                    "reports.hr-giris-cikis.view", "reports.hr-puantaj.view",
                    "reports.hr-maas-gecmisi.view", "reports.hr-egitim-katilim.view",
                    "reports.hr-izin-raporu.view", "reports.hr-bordro-detay.view",
                    "reports.hr.all-companies",
                    "dashboards.hr-analytics.view", "dashboards.hr-finansal.view",
                    // Sales reports
                    "reports.satis-ozet.view", "reports.stok-durum.view",
                    // Finance reports + dashboard
                    "reports.fin-banka-hareketleri.view", "reports.fin-kasa-hareketleri.view",
                    "reports.fin-faturalar.view", "reports.fin-cek-senet.view",
                    "reports.fin-cari-hareketler.view", "reports.fin.all-companies",
                    "dashboards.fin-analytics.view"
            )),
            Map.entry("REPORT_VIEWER", Set.of("REPORT_VIEW")),
            Map.entry("REPORT_MANAGER", Set.of("REPORT_VIEW", "REPORT_EXPORT", "REPORT_MANAGE")),
            // Sistem / yönetim rolleri
            Map.entry("USER_MANAGE", Set.of("user-read", "user-create", "user-update", "user-delete", "user-export", "user-import")),
            Map.entry("ROLE_MANAGE", Set.of("role-manage")),
            Map.entry("PERMISSION_MANAGE", Set.of("permission-manage", "permission-scope-manage")),
            Map.entry("SYSTEM_CONFIGURE", Set.of("system-configure")),
            Map.entry("AUDIT_READ", Set.of("audit-read")),
            Map.entry("USER_MANAGER", Set.of("VIEW_USERS", "MANAGE_USERS")),
            Map.entry("USER_VIEWER", Set.of("VIEW_USERS")),
            Map.entry("PURCHASE_MANAGER", Set.of("VIEW_USERS", "APPROVE_PURCHASE")),
            Map.entry("WAREHOUSE_OPERATOR", Set.of("VIEW_USERS", "MANAGE_WAREHOUSE")),
            // Finance reporting roles
            Map.entry("FINANCE_VIEWER", Set.of(
                    "REPORT_VIEW",
                    "reports.fin-banka-hareketleri.view", "reports.fin-kasa-hareketleri.view",
                    "reports.fin-faturalar.view", "reports.fin-cek-senet.view",
                    "reports.fin-cari-hareketler.view",
                    "dashboards.fin-analytics.view"
            )),
            Map.entry("FINANCE_MANAGER", Set.of(
                    "REPORT_VIEW", "REPORT_EXPORT",
                    "reports.fin-banka-hareketleri.view", "reports.fin-kasa-hareketleri.view",
                    "reports.fin-faturalar.view", "reports.fin-cek-senet.view",
                    "reports.fin-cari-hareketler.view", "reports.fin.all-companies",
                    "dashboards.fin-analytics.view"
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
                        rolePermission.setPermission(permission);
                        rolePermissionRepository.save(rolePermission);
                        log.info("Linked permission {} to role {}", permission.getCode(), role.getName());
                    });
        });
    }
}
