package com.example.permission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.example.permission.model.GrantType;
import com.example.permission.model.PermissionType;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * board #2593 — the ENDPOINT_ADMIN_VIEWER role (V21) only closes the gap if its single granule
 * resolves to the tuple the endpoint-admin grid actually checks:
 * {@code @RequireModule(module = "endpoint-admin", relation = "can_view")}.
 *
 * <p>This pins the contract V21 depends on. The migration writes
 * {@code (MODULE, "endpoint-admin", VIEW)}; if TupleSyncService ever changed how a MODULE/VIEW
 * granule is keyed or which relation it maps to, the role would silently stop granting access —
 * a grant API that returns 200 while the target stays 403, which is exactly the defect #2593
 * exists to remove. A unit test catches that long before a live smoke would.
 */
class EndpointAdminViewerGranuleTest {

    private static TupleSyncService newService() {
        return new TupleSyncService(
                mock(com.example.commonauth.openfga.OpenFgaAuthzService.class),
                mock(RolePermissionRepository.class),
                mock(UserRoleAssignmentRepository.class),
                mock(AuthzVersionService.class),
                null);
    }

    private static RolePermission granule(String type, String key, GrantType grant) {
        Role role = new Role();
        role.setName("ENDPOINT_ADMIN_VIEWER");
        RolePermission rp = new RolePermission();
        rp.setRole(role);
        rp.setPermissionType(PermissionType.valueOf(type));
        rp.setPermissionKey(key);
        rp.setGrantType(grant);
        return rp;
    }

    @Test
    @DisplayName("the V21 granule keys on MODULE:endpoint-admin and keeps its VIEW grant")
    void v21GranuleResolvesToEndpointAdminModuleViewGrant() {
        var effective = newService().resolveEffectiveGrants(
                List.of(granule("MODULE", "endpoint-admin", GrantType.VIEW)));

        // The composite key is "<TYPE>:<permission_key>" — the key must be the exact FGA object
        // id 'endpoint-admin', NOT the uppercase 'ENDPOINT_ADMIN', or the tuple lands on the
        // wrong object and the guard is never satisfied.
        assertThat(effective).containsOnlyKeys("MODULE:endpoint-admin");
        assertThat(effective.get("MODULE:endpoint-admin").grantType())
                .as("a read role must stay VIEW, never silently widen to MANAGE")
                .isEqualTo(GrantType.VIEW);
    }

    @Test
    @DisplayName("an uppercase key would land on the wrong FGA object — regression guard")
    void uppercaseKeyWouldMissTheGuard() {
        var effective = newService().resolveEffectiveGrants(
                List.of(granule("MODULE", "ENDPOINT_ADMIN", GrantType.VIEW)));

        // Demonstrates why V21 uses lowercase: the composite key differs, so this granule would
        // produce module:ENDPOINT_ADMIN — which @RequireModule(module="endpoint-admin") never reads.
        assertThat(effective).doesNotContainKey("MODULE:endpoint-admin");
        assertThat(effective).containsKey("MODULE:ENDPOINT_ADMIN");
    }
}
