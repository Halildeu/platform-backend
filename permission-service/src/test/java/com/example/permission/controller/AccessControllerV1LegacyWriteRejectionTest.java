package com.example.permission.controller;

import com.example.permission.dto.v1.BulkPermissionsRequestDto;
import com.example.permission.dto.v1.RolePermissionsUpdateRequestDto;
import com.example.permission.model.PermissionModel;
import com.example.permission.model.Role;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.service.AccessRoleService;
import com.example.permission.service.PermissionService;
import com.example.permission.service.TupleSyncService;
import com.example.permission.service.UserScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Codex 019dd818 iter-16 (Plan C) regression: legacy write boundary enforcement.
 *
 * <p>Granule-managed roles must reject the legacy permission write endpoints —
 * mixing both writers re-creates the FK + granule mixed state V15/V16 cleaned
 * up. This suite locks the 409 behaviour for both endpoints and verifies the
 * legacy code path still works for LEGACY-mode roles (backward compat).
 *
 * <ul>
 *   <li>{@code PATCH /v1/roles/{id}/permissions/bulk} on GRANULE role → 409</li>
 *   <li>{@code PUT   /v1/roles/{id}/permissions} on GRANULE role → 409</li>
 *   <li>Both endpoints on LEGACY role → service is invoked normally</li>
 *   <li>Unknown role → 404 (vs silent service-layer error)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AccessControllerV1LegacyWriteRejectionTest {

    @Mock private AccessRoleService accessRoleService;
    @Mock private UserScopeService userScopeService;
    @Mock private UserRoleAssignmentRepository assignmentRepository;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private PermissionService permissionService;
    @Mock private TupleSyncService tupleSyncService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AccessControllerV1 controller;

    @BeforeEach
    void setUp() {
        controller = new AccessControllerV1(
                accessRoleService,
                userScopeService,
                assignmentRepository,
                rolePermissionRepository,
                roleRepository,
                permissionService,
                tupleSyncService,
                eventPublisher
        );
    }

    @Test
    void bulkPermissions_onGranuleRole_throws409() {
        Long roleId = 9L;
        Role granuleRole = roleWithModel(roleId, PermissionModel.GRANULE);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(granuleRole));

        BulkPermissionsRequestDto request = new BulkPermissionsRequestDto();
        request.setModuleKey("USER_MANAGEMENT");
        request.setLevel("MANAGE");

        assertThatThrownBy(() -> controller.bulkPermissions(roleId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT)
                .hasMessageContaining("granule-managed");

        // Critical: service must NOT be invoked. If it were, AccessRoleService
        // would write FK rows and re-create mixed state.
        verify(accessRoleService, never()).bulkUpdateModuleLevel(any(), any(), any(), any(), any());
    }

    @Test
    void updateRolePermissions_onGranuleRole_throws409() {
        Long roleId = 2L;
        Role granuleRole = roleWithModel(roleId, PermissionModel.GRANULE);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(granuleRole));

        RolePermissionsUpdateRequestDto request = new RolePermissionsUpdateRequestDto();

        assertThatThrownBy(() -> controller.updateRolePermissions(roleId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.CONFLICT);

        verify(accessRoleService, never()).updateRolePermissions(any(), any(), any());
    }

    @Test
    void bulkPermissions_onLegacyRole_invokesService() {
        // Backward compat: LEGACY roles continue to flow through the service
        // exactly as before iter-16. The 409 boundary applies only to GRANULE.
        Long roleId = 14L;
        Role legacyRole = roleWithModel(roleId, PermissionModel.LEGACY);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(legacyRole));

        BulkPermissionsRequestDto request = new BulkPermissionsRequestDto();
        request.setModuleKey("USER_MANAGEMENT");
        request.setLevel("VIEW");

        controller.bulkPermissions(roleId, request);

        verify(accessRoleService, times(1))
                .bulkUpdateModuleLevel(any(), any(), any(), any(), any());
    }

    @Test
    void updateRolePermissions_onLegacyRole_invokesService() {
        Long roleId = 11L;
        Role legacyRole = roleWithModel(roleId, PermissionModel.LEGACY);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(legacyRole));

        controller.updateRolePermissions(roleId, new RolePermissionsUpdateRequestDto());

        verify(accessRoleService, times(1)).updateRolePermissions(any(), any(), any());
    }

    @Test
    void bulkPermissions_onMissingRole_throws404() {
        Long roleId = 999L;
        when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

        BulkPermissionsRequestDto request = new BulkPermissionsRequestDto();
        request.setModuleKey("X");
        request.setLevel("VIEW");

        assertThatThrownBy(() -> controller.bulkPermissions(roleId, request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(accessRoleService, never()).bulkUpdateModuleLevel(any(), any(), any(), any(), any());
    }

    private Role roleWithModel(Long id, PermissionModel model) {
        Role role = new Role();
        role.setId(id);
        role.setName("ROLE_" + id);
        role.setPermissionModel(model);
        return role;
    }
}
