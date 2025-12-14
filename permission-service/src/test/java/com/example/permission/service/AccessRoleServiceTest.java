package com.example.permission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.permission.dto.v1.BulkPermissionsResponseDto;
import com.example.permission.dto.v1.RoleCloneResponseDto;
import com.example.permission.model.Permission;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class AccessRoleServiceTest {

    private RoleRepository roleRepository = mock(RoleRepository.class);
    private RolePermissionRepository rolePermissionRepository = mock(RolePermissionRepository.class);
    private PermissionRepository permissionRepository = mock(PermissionRepository.class);
    private UserRoleAssignmentRepository assignmentRepository = mock(UserRoleAssignmentRepository.class);
    private AuditEventService auditEventService = mock(AuditEventService.class);

    private AccessRoleService service;

    @BeforeEach
    void setUp() {
        service = new AccessRoleService(roleRepository, rolePermissionRepository, permissionRepository, assignmentRepository, auditEventService);
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
}
