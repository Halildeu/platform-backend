package com.example.permission.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codex 019dd818 iter-13 (Plan B) regression coverage:
 * Role aggregate helpers underpin the granule-replace fix in
 * {@code AccessControllerV1.updateRoleGranules}. Without these helpers, the
 * controller fell back to JPQL bulk DELETE + cascade save, which leaked
 * stale FK rows back into the database.
 */
class RoleAggregateHelpersTest {

    @Test
    void clearRolePermissions_emptiesCollection() {
        Role role = new Role();
        role.addRolePermission(granule(role, "USER_MANAGEMENT"));
        role.addRolePermission(granule(role, "ACCESS"));
        assertThat(role.getRolePermissions()).hasSize(2);

        role.clearRolePermissions();

        assertThat(role.getRolePermissions()).isEmpty();
    }

    @Test
    void addRolePermission_setsBackReference() {
        Role role = new Role();
        role.setId(42L);
        RolePermission rp = new RolePermission();
        rp.setPermissionType(PermissionType.MODULE);
        rp.setPermissionKey("USER_MANAGEMENT");
        rp.setGrantType(GrantType.VIEW);

        role.addRolePermission(rp);

        assertThat(rp.getRole()).isSameAs(role);
        assertThat(role.getRolePermissions()).containsExactly(rp);
    }

    @Test
    void clearThenAdd_simulatesAggregateNativeReplace() {
        // Codex iter-13 use case: replace-all semantics on the JPA aggregate.
        // The controller calls clear() then add() for each new granule;
        // orphanRemoval=true + cascade=ALL handle DB I/O without bulk DML.
        Role role = new Role();
        role.addRolePermission(granule(role, "USER_MANAGEMENT"));
        role.addRolePermission(granule(role, "ACCESS"));
        role.addRolePermission(granule(role, "REPORT"));
        assertThat(role.getRolePermissions()).hasSize(3);

        // Simulate updateRoleGranules: clear + add 1 new granule
        role.clearRolePermissions();
        role.addRolePermission(granule(role, "USER_MANAGEMENT"));

        assertThat(role.getRolePermissions()).hasSize(1);
        assertThat(role.getRolePermissions().iterator().next().getPermissionKey())
                .isEqualTo("USER_MANAGEMENT");
    }

    private static RolePermission granule(Role role, String key) {
        RolePermission rp = new RolePermission(role, PermissionType.MODULE, key, GrantType.VIEW);
        return rp;
    }
}
