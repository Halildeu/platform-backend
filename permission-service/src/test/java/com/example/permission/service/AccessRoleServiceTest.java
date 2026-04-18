package com.example.permission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.permission.dto.access.AccessRoleDto;
import com.example.permission.dto.v1.BulkPermissionsResponseDto;
import com.example.permission.dto.v1.RoleCloneResponseDto;
import com.example.permission.model.GrantType;
import com.example.permission.model.Permission;
import com.example.permission.model.PermissionType;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AccessRoleServiceTest {

    private RoleRepository roleRepository = mock(RoleRepository.class);
    private RolePermissionRepository rolePermissionRepository = mock(RolePermissionRepository.class);
    private PermissionRepository permissionRepository = mock(PermissionRepository.class);
    private UserRoleAssignmentRepository assignmentRepository = mock(UserRoleAssignmentRepository.class);
    private AuditEventService auditEventService = mock(AuditEventService.class);
    private com.example.commonauth.openfga.OpenFgaAuthzService authzService = mock(com.example.commonauth.openfga.OpenFgaAuthzService.class);
    private AuthzVersionService authzVersionService = mock(AuthzVersionService.class);
    private TupleSyncService tupleSyncService = mock(TupleSyncService.class);

    private AccessRoleService service;
    private PermissionCatalogService permissionCatalogService;

    @BeforeEach
    void setUp() {
        permissionCatalogService = new PermissionCatalogService();
        service = new AccessRoleService(roleRepository, rolePermissionRepository, permissionRepository, assignmentRepository, auditEventService, authzService, authzVersionService, tupleSyncService, mock(org.springframework.context.ApplicationEventPublisher.class), permissionCatalogService);
        when(assignmentRepository.countByRoleAndActiveTrue(any())).thenReturn(0L);
    }

    @Test
    void cloneRole_copiesPermissions() {
        Role source = roleWithPermissions("USER_MANAGER", List.of(permission("VIEW_USERS"), permission("MANAGE_USERS")));
        source.setId(10L);
        when(roleRepository.findById(10L)).thenReturn(Optional.of(source));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> { Role r = inv.getArgument(0); r.setId(20L); return r; });

        RoleCloneResponseDto result = service.cloneRole(10L, "USER_MANAGER_CLONE", null, 1L);

        assertThat(result.getRole()).isNotNull();
        assertThat(result.getRole().getId()).isEqualTo(20L);
        verify(rolePermissionRepository, atLeastOnce()).save(any(RolePermission.class));
    }

    @Test
    void bulkUpdate_purchase_manage_addsApprovePurchase() {
        Role role = roleWithPermissions("PURCHASE_MANAGER", List.of());
        role.setId(1L);
        when(roleRepository.findById(1L)).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));
        when(permissionRepository.findAll()).thenReturn(List.of(permission("APPROVE_PURCHASE"), permission("VIEW_PURCHASE")));

        BulkPermissionsResponseDto result = service.bulkUpdateModuleLevel(List.of(1L), "PURCHASE", "Satın Alma", "MANAGE", 1L);
        List<Long> updated = result.getUpdatedRoleIds();
        assertThat(updated).contains(1L);
    }

    @Test
    void bulkUpdate_warehouse_view_addsViewWarehouseOnly() {
        Role role = roleWithPermissions("WAREHOUSE_OPERATOR", List.of());
        role.setId(2L);
        when(roleRepository.findById(2L)).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));
        when(permissionRepository.findAll()).thenReturn(List.of(permission("MANAGE_WAREHOUSE"), permission("VIEW_WAREHOUSE")));

        BulkPermissionsResponseDto result = service.bulkUpdateModuleLevel(List.of(2L), "WAREHOUSE", "Depo", "VIEW", 1L);
        List<Long> updated = result.getUpdatedRoleIds();
        assertThat(updated).contains(2L);
    }

    @Test
    void bulkUpdate_userManagement_none_removesAll() {
        Role role = roleWithPermissions("USER_MANAGER", List.of(permission("MANAGE_USERS"), permission("VIEW_USERS")));
        role.setId(3L);
        when(roleRepository.findById(3L)).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));
        when(permissionRepository.findAll()).thenReturn(List.of(permission("MANAGE_USERS"), permission("VIEW_USERS")));

        BulkPermissionsResponseDto result = service.bulkUpdateModuleLevel(List.of(3L), "USER_MANAGEMENT", "Kullanıcı Yönetimi", "NONE", 1L);
        List<Long> updated = result.getUpdatedRoleIds();
        assertThat(updated).contains(3L);
    }

    // P1.2: canonical module key regression suite.
    // /v1/roles response policies[].moduleKey MUST match /v1/authz/catalog seed keys
    // (REPORT, PURCHASE, WAREHOUSE, USER_MANAGEMENT, ...) regardless of JVM locale.
    // Prior bug: label-derived normalization produced "RAPORLAMA" in tr_TR locale
    // because AccessRoleService.deriveModuleIdentity() only special-cased USERS.

    @Test
    void deriveModuleIdentity_reportCode_returnsCanonicalKey() {
        String[] identity = service.deriveModuleIdentity(
                List.of(rpWith(permissionWithModule("REPORT_SALES_VIEW", "Raporlama"))),
                "Raporlama");
        assertThat(identity[0]).isEqualTo("REPORT");
        assertThat(identity[1]).isEqualTo("Raporlama");
    }

    @Test
    void deriveModuleIdentity_purchaseCode_returnsCanonicalKey() {
        String[] identity = service.deriveModuleIdentity(
                List.of(rpWith(permissionWithModule("VIEW_PURCHASE", "Satın Alma"))),
                "Satın Alma");
        assertThat(identity[0]).isEqualTo("PURCHASE");
        assertThat(identity[1]).isEqualTo("Satın Alma");
    }

    @Test
    void deriveModuleIdentity_warehouseCode_returnsCanonicalKey() {
        String[] identity = service.deriveModuleIdentity(
                List.of(rpWith(permissionWithModule("MANAGE_WAREHOUSE", "Depo"))),
                "Depo");
        assertThat(identity[0]).isEqualTo("WAREHOUSE");
        assertThat(identity[1]).isEqualTo("Depo");
    }

    @Test
    void deriveModuleIdentity_userManagementCode_returnsCanonicalKey() {
        String[] identity = service.deriveModuleIdentity(
                List.of(rpWith(permissionWithModule("VIEW_USERS", "Kullanıcı Yönetimi"))),
                "Kullanıcı Yönetimi");
        assertThat(identity[0]).isEqualTo("USER_MANAGEMENT");
        assertThat(identity[1]).isEqualTo("Kullanıcı Yönetimi");
    }

    @Test
    void deriveModuleIdentity_accessCode_returnsCanonicalKey() {
        String[] identity = service.deriveModuleIdentity(
                List.of(rpWith(permissionWithModule("access-view", "Erişim Yönetimi"))),
                "Erişim Yönetimi");
        assertThat(identity[0]).isEqualTo("ACCESS");
        // Label comes from PermissionCatalogService (single source of truth),
        // not fallbackLabel — guards against drift with /v1/authz/catalog response.
        assertThat(identity[1]).isEqualTo("Erişim Yönetimi");
    }

    @Test
    void deriveModuleIdentity_approvePurchaseCode_returnsPurchaseCanonical() {
        // APPROVE_PURCHASE resolves to MODULE granule in RolePermissionGranuleDefaults
        // (`module("PURCHASE", upperCode)` branch) — canonical PURCHASE.
        String[] identity = service.deriveModuleIdentity(
                List.of(rpWith(permissionWithModule("APPROVE_PURCHASE", "Satın Alma"))),
                "Satın Alma");
        assertThat(identity[0]).isEqualTo("PURCHASE");
        assertThat(identity[1]).isEqualTo("Satın Alma");
    }

    // Note: non-MODULE granules (e.g. `reports.sales.view` → PermissionType.REPORT,
    // system-configure → PermissionType.ACTION) intentionally fall back to label-
    // derived keys with a WARN log. PermissionCatalogService.REPORTS entries have
    // per-report parent module assignments (HR_REPORTS → USER_MANAGEMENT,
    // SALES_REPORTS → PURCHASE) that a simple code prefix rule cannot reproduce.
    // Catalog-driven parent lookup for non-MODULE granules is a separate story —
    // P1.2 scope is MODULE granule locale regression only.

    @Test
    void deriveModuleIdentity_labelComesFromCatalogNotFallback() {
        // Even if fallbackLabel (DB moduleName) is drifted, canonical label
        // from PermissionCatalogService wins. Prevents /v1/authz/catalog drift.
        String[] identity = service.deriveModuleIdentity(
                List.of(rpWith(permissionWithModule("REPORT_SALES_VIEW", "FARKLI_LABEL"))),
                "FARKLI_LABEL");
        assertThat(identity[0]).isEqualTo("REPORT");
        assertThat(identity[1]).isEqualTo("Raporlama");  // canonical, not "FARKLI_LABEL"
    }

    @Test
    void deriveModuleIdentity_auditCode_returnsCanonicalKey() {
        String[] identity = service.deriveModuleIdentity(
                List.of(rpWith(permissionWithModule("audit-view", "Denetim"))),
                "Denetim");
        assertThat(identity[0]).isEqualTo("AUDIT");
        assertThat(identity[1]).isEqualTo("Denetim");
    }

    @Test
    void deriveModuleIdentity_unknownCode_fallsBackToLabelDerivedKey() {
        String[] identity = service.deriveModuleIdentity(
                List.of(rpWith(permissionWithModule("unknown-xyz-code", "Bilinmeyen Modül"))),
                "Bilinmeyen Modül");
        assertThat(identity[1]).isEqualTo("Bilinmeyen Modül");
        // key is legacy label-derived (WARN log emitted); value is not asserted here
        // because it's intentionally non-canonical fallback — caller must add a
        // RolePermissionGranuleDefaults mapping to canonicalize.
    }

    @Test
    void deriveModuleIdentity_turkishLocale_reportKeyRemainsCanonical() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            String[] identity = service.deriveModuleIdentity(
                    List.of(rpWith(permissionWithModule("REPORT_SALES_VIEW", "Raporlama"))),
                    "Raporlama");
            assertThat(identity[0]).isEqualTo("REPORT");
        } finally {
            Locale.setDefault(previous);
        }
    }

    private Permission permissionWithModule(String code, String moduleName) {
        Permission p = new Permission();
        p.setCode(code);
        p.setModuleName(moduleName);
        return p;
    }

    private RolePermission rpWith(Permission permission) {
        RolePermission rp = new RolePermission();
        rp.setPermission(permission);
        return rp;
    }

    private Role roleWithPermissions(String name, List<Permission> permissions) {
        Role r = new Role();
        r.setName(name);
        for (Permission p : permissions) {
            RolePermission rp = new RolePermission();
            rp.setRole(r);
            rp.setPermission(p);
            r.getRolePermissions().add(rp);
        }
        return r;
    }

    private Permission permission(String code) {
        Permission p = new Permission();
        p.setCode(code);
        p.setModuleName(code.contains("WAREHOUSE") ? "Depo" : code.contains("PURCHASE") ? "Satın Alma" : "Kullanıcı Yönetimi");
        return p;
    }

    // STORY-0318/OI-03: granule-only role (permission=null, type/key/grant populated)
    // regression suite. Canary roles created via PUT /v1/roles/{id}/granules go
    // through this path and pre-fix would NPE at deriveModuleIdentity / toDto /
    // deriveLevel / snapshotRole / toResponse.

    @Test
    void deriveModuleIdentity_granuleOnlyModuleRow_returnsPermissionKey() {
        // MODULE granule with permission=null must use permissionKey directly
        // (canonical module key) instead of dereferencing the legacy Permission.
        RolePermission rp = new RolePermission();
        rp.setPermissionType(PermissionType.MODULE);
        rp.setPermissionKey("ACCESS");
        rp.setGrantType(GrantType.MANAGE);
        String[] identity = service.deriveModuleIdentity(List.of(rp), "ACCESS");
        assertThat(identity[0]).isEqualTo("ACCESS");
        assertThat(identity[1]).isNotBlank();
    }

    @Test
    void getRole_withMixedGranules_skipsActionGranulesFromPolicies() {
        // Canary-style role: one MODULE granule (ACCESS MANAGE) + one ACTION
        // granule (DELETE_PO ALLOW). Policies summary must contain only the
        // module row; ACTION must not leak as a pseudo-module badge.
        Role role = new Role();
        role.setId(42L);
        role.setName("CANARY_RESTRICTED");

        RolePermission modRp = new RolePermission();
        modRp.setRole(role);
        modRp.setPermissionType(PermissionType.MODULE);
        modRp.setPermissionKey("ACCESS");
        modRp.setGrantType(GrantType.MANAGE);
        RolePermission actionRp = new RolePermission();
        actionRp.setRole(role);
        actionRp.setPermissionType(PermissionType.ACTION);
        actionRp.setPermissionKey("DELETE_PO");
        actionRp.setGrantType(GrantType.ALLOW);
        role.getRolePermissions().add(modRp);
        role.getRolePermissions().add(actionRp);

        when(roleRepository.findById(42L)).thenReturn(Optional.of(role));

        AccessRoleDto dto = service.getRole(42L);

        assertThat(dto.policies()).hasSize(1);
        assertThat(dto.policies().get(0).moduleKey()).isEqualTo("ACCESS");
        assertThat(dto.policies().stream().map(p -> p.moduleKey()))
                .doesNotContain("DELETE_PO");
    }
}
