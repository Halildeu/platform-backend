package com.example.permission.controller;

import com.example.permission.model.Role;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.service.AccessRoleService;
import com.example.permission.service.PermissionService;
import com.example.permission.service.TupleSyncService;
import com.example.permission.service.UserScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AG-028 revoke-orphan fix (platform-k8s-gitops #1272) — wiring guard for
 * {@code DELETE /api/v1/roles/{roleId}/members/{userId}}.
 *
 * <p>The controller delegates each matching assignment to
 * {@code PermissionService.revokeRole}, which is the central chokepoint that
 * performs the granule-aware OpenFGA reconciliation (in-tx, fail-loud) — see
 * {@code PermissionServiceRevokeReconcileTest}. This test just guards the
 * controller's match-and-delegate logic.
 */
@ExtendWith(MockitoExtension.class)
class AccessControllerV1RemoveRoleMemberTest {

    @Mock AccessRoleService accessRoleService;
    @Mock UserScopeService userScopeService;
    @Mock UserRoleAssignmentRepository assignmentRepository;
    @Mock RolePermissionRepository rolePermissionRepository;
    @Mock RoleRepository roleRepository;
    @Mock PermissionService permissionService;
    @Mock TupleSyncService tupleSyncService;
    @Mock ApplicationEventPublisher eventPublisher;

    AccessControllerV1 controller;

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
                eventPublisher);
    }

    private UserRoleAssignment assignmentToRole(long roleId) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(roleId);
        UserRoleAssignment assignment = mock(UserRoleAssignment.class);
        when(assignment.getRole()).thenReturn(role);
        return assignment;
    }

    @Test
    @DisplayName("removeRoleMember revokes the matching assignment (reconcile happens inside revokeRole)")
    void removeRoleMember_revokesMatchingAssignment() {
        var assignment = assignmentToRole(21L);
        when(assignment.getId()).thenReturn(500L);
        when(assignmentRepository.findActiveAssignments(9004L)).thenReturn(List.of(assignment));

        controller.removeRoleMember(21L, 9004L);

        verify(permissionService).revokeRole(500L, null);
    }

    @Test
    @DisplayName("removeRoleMember does nothing when the user is not a member of the role")
    void removeRoleMember_noMatchNoRevoke() {
        // user is assigned to a DIFFERENT role (99), not the requested role 21
        var assignment = assignmentToRole(99L);
        when(assignmentRepository.findActiveAssignments(9004L)).thenReturn(List.of(assignment));

        controller.removeRoleMember(21L, 9004L);

        verify(permissionService, never()).revokeRole(anyLong(), any());
    }
}
