package com.example.permission.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.permission.model.GrantType;
import com.example.permission.model.Permission;
import com.example.permission.model.PermissionType;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AG-028 revoke-orphan fix (platform-k8s-gitops #1272) — service-level guard
 * for the OpenFGA privilege-revocation correctness gap found live on testai
 * (2026-06-04): revoking a granule grant left the {@code can_manage}/
 * {@code can_view} tuple orphaned in OpenFGA, so a revoked (or disabled) user
 * silently kept access.
 *
 * <p>Root cause: {@link TupleSyncService#refreshFeatureTuples} derived its
 * delete targets from the user's CURRENT role-permission rows, so a
 * {@code (type,key)} grant — or an entire role — that was <em>removed</em> had
 * no row to drive the delete and the tuple survived. The fix enumerates the
 * user's <em>actual stored</em> feature tuples from OpenFGA ground truth via
 * {@link OpenFgaAuthzService#readUserTuples} and deletes exactly those before
 * re-syncing.
 *
 * <p>These mock-and-verify tests mirror
 * {@link TupleSyncServiceRelationAlignmentTest}; the real grant→allowed→
 * revoke→deny assertion against a live OpenFGA is in
 * {@code TupleSyncRevokeDeletionIntegrationTest} (Tag "integration").
 */
@ExtendWith(MockitoExtension.class)
class TupleSyncServiceRevokeDeletionTest {

    @Mock OpenFgaAuthzService authzService;
    @Mock RolePermissionRepository rolePermissionRepository;
    @Mock UserRoleAssignmentRepository assignmentRepository;
    @Mock AuthzVersionService authzVersionService;

    TupleSyncService service;

    @BeforeEach
    void setUp() {
        service = new TupleSyncService(
                authzService,
                rolePermissionRepository,
                assignmentRepository,
                authzVersionService,
                null);
    }

    private static ClientTupleKeyWithoutCondition stored(String userId, String relation, String type, String id) {
        return OpenFgaAuthzService.deleteTupleKey(userId, relation, type, id);
    }

    private UserRoleAssignment assignmentToRole(long roleId) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(roleId);
        UserRoleAssignment assignment = mock(UserRoleAssignment.class);
        when(assignment.getRole()).thenReturn(role);
        return assignment;
    }

    private UserRoleAssignment assignmentWithUserId(long userId) {
        UserRoleAssignment assignment = mock(UserRoleAssignment.class);
        when(assignment.getUserId()).thenReturn(userId);
        return assignment;
    }

    /** Granule-shape RolePermission (permission FK null). */
    private RolePermission granule(PermissionType type, String key, GrantType grant) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn("granule-role");
        RolePermission rp = mock(RolePermission.class);
        when(rp.getPermissionType()).thenReturn(type);
        when(rp.getPermissionKey()).thenReturn(key);
        when(rp.getGrantType()).thenReturn(grant);
        when(rp.getRole()).thenReturn(role);
        return rp;
    }

    /** Legacy-shape RolePermission (permission FK set, granule fields null). */
    private RolePermission legacy(String code) {
        Permission perm = mock(Permission.class);
        when(perm.getCode()).thenReturn(code);
        RolePermission rp = mock(RolePermission.class);
        when(rp.getPermission()).thenReturn(perm);
        return rp;
    }

    @Test
    @DisplayName("Bug A — granule emptied, member still assigned: orphaned tuple deleted (read from FGA), no re-write")
    @SuppressWarnings("unchecked")
    void granuleEmptied_deletesOrphanTupleNotRewrite() {
        // role 21 still assigned to the user, but its granules were emptied via
        // PUT /roles/21/granules [] → no current rows for the removed grant.
        var assignment = assignmentToRole(21L);
        when(assignmentRepository.findActiveAssignments(9003L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of());
        when(authzService.readUserTuples(eq("9003"), any()))
                .thenReturn(List.of(stored("9003", "can_manage", "module", "endpoint-admin")));

        // skip=true mirrors the propagateRoleChange (RoleChangeEvent) path.
        service.refreshFeatureTuples("9003", true);

        ArgumentCaptor<List<ClientTupleKeyWithoutCondition>> captor = ArgumentCaptor.forClass(List.class);
        verify(authzService).deleteTuples(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getRelation()).isEqualTo("can_manage");
        assertThat(captor.getValue().get(0).getObject()).isEqualTo("module:endpoint-admin");

        // the removed grant must NOT be re-written
        verify(authzService, never()).writeTuple(anyString(), anyString(), anyString(), anyString());
        verify(authzService, never()).writeTuples(anyList());
    }

    @Test
    @DisplayName("Bug B — member removed from last role: orphaned tuple deleted + authz version bumped")
    void memberRemovedFromLastRole_deletesOrphanAndBumpsVersion() {
        // DELETE /roles/21/members/9003 revoked the user's only assignment.
        when(assignmentRepository.findActiveAssignments(9003L)).thenReturn(List.of());
        when(authzService.readUserTuples(eq("9003"), any()))
                .thenReturn(List.of(stored("9003", "can_manage", "module", "endpoint-admin")));

        service.refreshFeatureTuples("9003"); // skip=false

        verify(authzService).deleteTuples(anyList());
        // revocation must propagate to downstream caches
        verify(authzVersionService).incrementVersion();
        // the no-active-roles branch must not query role permissions or re-write
        verify(rolePermissionRepository, never()).findByRoleIdIn(any());
        verify(authzService, never()).writeTuple(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Ground truth: deletes whatever FGA actually stores, independent of current DB rows (stale-relation safety)")
    @SuppressWarnings("unchecked")
    void deletesStoredTuplesIndependentOfDbRows() {
        // FGA stores BOTH can_view and can_manage for the key (VIEW→MANAGE
        // history) but the granule was removed entirely, so the DB has no row.
        // The old DB-derived logic would have deleted nothing; the new logic
        // deletes exactly the stored tuples.
        var assignment = assignmentToRole(21L);
        when(assignmentRepository.findActiveAssignments(9003L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of());
        when(authzService.readUserTuples(eq("9003"), any())).thenReturn(List.of(
                stored("9003", "can_view", "module", "endpoint-admin"),
                stored("9003", "can_manage", "module", "endpoint-admin")));

        service.refreshFeatureTuples("9003", true);

        ArgumentCaptor<List<ClientTupleKeyWithoutCondition>> captor = ArgumentCaptor.forClass(List.class);
        verify(authzService).deleteTuples(captor.capture());
        assertThat(captor.getValue())
                .extracting(ClientTupleKeyWithoutCondition::getRelation)
                .containsExactlyInAnyOrder("can_view", "can_manage");
    }

    @Test
    @DisplayName("Option B — spares LEGACY module tuples (mixed user): deletes only the orphaned granule tuple")
    @SuppressWarnings("unchecked")
    void reconcileSparesLegacyTuples() {
        // The user's active role now grants ONLY legacy VIEW_ACCESS (the granule
        // endpoint-admin grant was removed). FGA still stores both the legacy
        // can_view module:ACCESS and the orphaned granule can_manage
        // module:endpoint-admin.
        var assignment = assignmentToRole(21L);
        var legacyAccess = legacy("VIEW_ACCESS");
        when(assignmentRepository.findActiveAssignments(9003L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of(legacyAccess));
        when(authzService.readUserTuples(eq("9003"), any())).thenReturn(List.of(
                stored("9003", "can_view", "module", "ACCESS"),
                stored("9003", "can_manage", "module", "endpoint-admin")));

        service.refreshFeatureTuples("9003", true);

        ArgumentCaptor<List<ClientTupleKeyWithoutCondition>> captor = ArgumentCaptor.forClass(List.class);
        verify(authzService).deleteTuples(captor.capture());
        assertThat(captor.getValue())
                .extracting(t -> t.getRelation() + "|" + t.getObject())
                .containsExactly("can_manage|module:endpoint-admin"); // legacy ACCESS spared
    }

    @Test
    @DisplayName("Option B — spares a granule tuple still justified by another active role (overlap): no delete")
    void reconcileSparesOverlappingGrant() {
        var assignment = assignmentToRole(21L);
        var stillGranted = granule(PermissionType.MODULE, "endpoint-admin", GrantType.MANAGE);
        when(assignmentRepository.findActiveAssignments(9003L)).thenReturn(List.of(assignment));
        // a remaining active role still grants can_manage module:endpoint-admin
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of(stillGranted));
        when(authzService.readUserTuples(eq("9003"), any()))
                .thenReturn(List.of(stored("9003", "can_manage", "module", "endpoint-admin")));

        service.refreshFeatureTuples("9003", true);

        // tuple is in complete-desired → not deleted at all
        verify(authzService, never()).deleteTuples(anyList());
    }

    @Test
    @DisplayName("Fail-loud — genuine deleteTuples failure propagates")
    void deleteTuplesFailurePropagates() {
        var assignment = assignmentToRole(21L);
        when(assignmentRepository.findActiveAssignments(9003L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of());
        when(authzService.readUserTuples(eq("9003"), any()))
                .thenReturn(List.of(stored("9003", "can_manage", "module", "endpoint-admin")));
        doThrow(new RuntimeException("openfga delete failed")).when(authzService).deleteTuples(anyList());

        assertThatThrownBy(() -> service.refreshFeatureTuples("9003", true))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Fail-loud — genuine granule write failure propagates")
    void granuleWriteFailurePropagates() {
        var assignment = assignmentToRole(21L);
        var grant = granule(PermissionType.MODULE, "endpoint-admin", GrantType.MANAGE);
        when(assignmentRepository.findActiveAssignments(9003L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of(grant));
        when(authzService.readUserTuples(eq("9003"), any())).thenReturn(List.of());
        doThrow(new RuntimeException("openfga write failed"))
                .when(authzService).writeTuple(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.refreshFeatureTuples("9003", true))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Fail-loud — propagateRoleChange throws and skips version bump when a user's reconcile fails")
    void propagateRoleChangeFailsLoudNoVersionBump() {
        var member = assignmentWithUserId(9003L);
        var assignment = assignmentToRole(21L);
        when(assignmentRepository.findByRoleIdAndActiveTrue(21L)).thenReturn(List.of(member));
        when(assignmentRepository.findActiveAssignments(9003L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of());
        when(authzService.readUserTuples(eq("9003"), any()))
                .thenThrow(new RuntimeException("openfga read failed"));

        assertThatThrownBy(() -> service.propagateRoleChange(21L))
                .isInstanceOf(RuntimeException.class);
        verify(authzVersionService, never()).incrementVersion();
    }

    @Test
    @DisplayName("Fail-loud — writeLegacyTuplesForUser (bulk legacy path) propagates a genuine write failure")
    void writeLegacyTuplesFailurePropagates() {
        var assignment = assignmentToRole(21L);
        var legacyManage = legacy("MANAGE_ACCESS");
        when(assignmentRepository.findActiveAssignments(9003L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of(legacyManage));
        doThrow(new RuntimeException("openfga write failed"))
                .when(authzService).writeTuple(anyString(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.writeLegacyTuplesForUser("9003", true))
                .isInstanceOf(RuntimeException.class);
    }
}
