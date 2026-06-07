package com.example.permission.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.scope.ScopeContextCache;
import com.example.permission.model.Permission;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for the #1275 (Codex 019ea233) GAIN composite
 * {@link TupleSyncService#refreshFeatureAndLegacyTuplesForUser}. The composite is a
 * thin orchestration over two already-fail-loud methods; these tests pin the two
 * properties that make it the safe Option-A end-state:
 * <ol>
 *   <li><b>Ordering + single bump</b>: refresh (ground-truth read/delete + granule
 *       write) BEFORE the positive legacy write BEFORE exactly one version bump +
 *       cache evict — matching {@code AccessRoleService.applyBulkPermissions}.</li>
 *   <li><b>Fail-loud</b>: a genuine OpenFGA failure in either step skips the version
 *       bump (a reconcile that could not write all tuples is never reported as
 *       success), so the in-tx caller rolls back.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class TupleSyncServiceTest {

    static {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    @Mock private OpenFgaAuthzService authzService;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private UserRoleAssignmentRepository assignmentRepository;
    @Mock private AuthzVersionService authzVersionService;
    @Mock private ScopeContextCache scopeContextCache;

    private TupleSyncService service;

    @BeforeEach
    void setUp() {
        service = new TupleSyncService(authzService, rolePermissionRepository,
                assignmentRepository, authzVersionService, scopeContextCache);
    }

    /**
     * One user with a single LEGACY FK role (VIEW_USERS → module:USER_MANAGEMENT#can_view).
     * No granule rows, so the granule refresh writes nothing and the only positive write
     * is the legacy one — which makes the collaborator order observable.
     */
    private void stubSingleLegacyRoleUser() {
        UserRoleAssignment a = new UserRoleAssignment();
        a.setUserId(101L);
        Role role = new Role();
        role.setId(7L);
        a.setRole(role);

        Permission perm = new Permission();
        perm.setCode("VIEW_USERS");
        RolePermission rp = new RolePermission();
        rp.setRole(role);
        rp.setPermission(perm);

        when(assignmentRepository.findActiveAssignments(101L)).thenReturn(List.of(a));
        when(rolePermissionRepository.findByRoleIdIn(List.of(7L))).thenReturn(List.of(rp));
    }

    @Test
    void refreshFeatureAndLegacyTuplesForUser_ordersRefreshThenLegacyThenSingleBump() {
        stubSingleLegacyRoleUser();
        when(authzService.readUserTuples(eq("101"), any())).thenReturn(List.of());

        service.refreshFeatureAndLegacyTuplesForUser("101");

        InOrder ordered = inOrder(authzService, authzVersionService, scopeContextCache);
        // (1) refresh.deleteStale reads ground truth
        ordered.verify(authzService).readUserTuples(eq("101"), any());
        // (2) writeLegacy writes the positive legacy module tuple
        ordered.verify(authzService).writeTuple("101", "can_view", "module", "USER_MANAGEMENT");
        // (3) exactly one version bump + cache evict, LAST, after both tuple sets are consistent
        ordered.verify(authzVersionService).incrementVersion();
        ordered.verify(scopeContextCache).evictUser("101");
        verify(authzVersionService, times(1)).incrementVersion(); // no double-count from the inner skip=true calls
    }

    @Test
    void refreshFeatureAndLegacyTuplesForUser_legacyWriteFailure_skipsVersionBump() {
        stubSingleLegacyRoleUser();
        when(authzService.readUserTuples(eq("101"), any())).thenReturn(List.of());
        doThrow(new RuntimeException("FGA write down"))
                .when(authzService).writeTuple("101", "can_view", "module", "USER_MANAGEMENT");

        assertThrows(RuntimeException.class, () -> service.refreshFeatureAndLegacyTuplesForUser("101"));

        verify(authzVersionService, never()).incrementVersion();
        verify(scopeContextCache, never()).evictUser(anyString());
    }

    @Test
    void refreshFeatureAndLegacyTuplesForUser_refreshFailure_skipsLegacyWriteAndBump() {
        stubSingleLegacyRoleUser();
        doThrow(new RuntimeException("FGA read down"))
                .when(authzService).readUserTuples(eq("101"), any());

        assertThrows(RuntimeException.class, () -> service.refreshFeatureAndLegacyTuplesForUser("101"));

        verify(authzService, never()).writeTuple(anyString(), anyString(), anyString(), anyString());
        verify(authzVersionService, never()).incrementVersion();
    }
}
