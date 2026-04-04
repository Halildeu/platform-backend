package com.example.permission.service;

import com.example.permission.dto.PermissionAssignRequest;
import com.example.permission.dto.PermissionResponse;
import com.example.permission.model.Permission;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    static {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    @Mock
    private UserRoleAssignmentRepository assignmentRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private AuditEventService auditEventService;

    @Mock
    private com.example.commonauth.openfga.OpenFgaAuthzService authzService;

    private ObjectMapper objectMapper;

    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        permissionService = new PermissionService(assignmentRepository, roleRepository, auditEventService, objectMapper, authzService);
    }

    @Test
    void assignRole_createsNewAssignmentAndReturnsPermissions() {
        PermissionAssignRequest request = buildRequest();
        Role role = buildRoleWithPermissions(3L, "MANAGER", "MANAGE_USERS", "VIEW_USERS");

        when(roleRepository.findById(request.getRoleId())).thenReturn(Optional.of(role));
        when(assignmentRepository.findActiveAssignment(
                request.getUserId(),
                request.getCompanyId(),
                request.getRoleId(),
                request.getProjectId(),
                request.getWarehouseId()
        )).thenReturn(Optional.empty());
        when(assignmentRepository.save(any(UserRoleAssignment.class))).thenAnswer(invocation -> {
            UserRoleAssignment assignment = invocation.getArgument(0);
            assignment.setId(42L);
            assignment.setAssignedAt(Instant.parse("2024-01-01T00:00:00Z"));
            return assignment;
        });
        when(auditEventService.recordEvent(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PermissionResponse response = permissionService.assignRole(request);

        assertThat(response.getAssignmentId()).isEqualTo(42L);
        assertThat(response.getRoleId()).isEqualTo(role.getId());
        assertThat(response.getPermissions()).containsExactlyInAnyOrder("MANAGE_USERS", "VIEW_USERS");
        assertThat(response.getModuleKey()).isNotBlank();
        assertThat(response.getModuleLabel()).isNotBlank();
        assertThat(response.isActive()).isTrue();

        ArgumentCaptor<UserRoleAssignment> assignmentCaptor = ArgumentCaptor.forClass(UserRoleAssignment.class);
        verify(assignmentRepository).save(assignmentCaptor.capture());
        UserRoleAssignment savedAssignment = assignmentCaptor.getValue();
        assertThat(savedAssignment.getUserId()).isEqualTo(request.getUserId());
        assertThat(savedAssignment.getCompanyId()).isEqualTo(request.getCompanyId());
        assertThat(savedAssignment.getRole()).isEqualTo(role);
        assertThat(savedAssignment.getAssignedBy()).isEqualTo(request.getAssignedBy());

        verify(auditEventService).recordEvent(any());
    }

    @Test
    void assignRole_throwsWhenActiveAssignmentExists() {
        PermissionAssignRequest request = buildRequest();
        Role role = buildRoleWithPermissions(3L, "MANAGER", "MANAGE_USERS");
        UserRoleAssignment existing = new UserRoleAssignment();
        existing.setId(99L);

        when(roleRepository.findById(request.getRoleId())).thenReturn(Optional.of(role));
        when(assignmentRepository.findActiveAssignment(
                request.getUserId(),
                request.getCompanyId(),
                request.getRoleId(),
                request.getProjectId(),
                request.getWarehouseId()
        )).thenReturn(Optional.of(existing));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> permissionService.assignRole(request));

        assertThat(exception.getMessage()).contains("Active assignment already exists");
        verify(assignmentRepository, never()).save(any());
        verify(auditEventService, never()).recordEvent(any());
    }

    private PermissionAssignRequest buildRequest() {
        PermissionAssignRequest request = new PermissionAssignRequest();
        request.setUserId(101L);
        request.setCompanyId(202L);
        request.setProjectId(303L);
        request.setWarehouseId(404L);
        request.setRoleId(3L);
        request.setAssignedBy(999L);
        return request;
    }

    private Role buildRoleWithPermissions(Long roleId, String name, String... permissionCodes) {
        Role role = new Role();
        role.setId(roleId);
        role.setName(name);

        Set<RolePermission> rolePermissions = Set.of(permissionCodes).stream()
                .map(code -> {
                    Permission permission = new Permission();
                    permission.setId((long) code.hashCode());
                    permission.setCode(code);
                    permission.setModuleName("Test Module");

                    RolePermission rolePermission = new RolePermission();
                    rolePermission.setId((long) code.hashCode());
                    rolePermission.setRole(role);
                    rolePermission.setPermission(permission);
                    return rolePermission;
                })
                .collect(java.util.stream.Collectors.toSet());

        role.setRolePermissions(rolePermissions);
        return role;
    }
}
