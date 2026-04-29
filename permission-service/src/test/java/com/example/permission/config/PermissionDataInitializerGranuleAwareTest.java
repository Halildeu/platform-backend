package com.example.permission.config;

import com.example.permission.model.GrantType;
import com.example.permission.model.Permission;
import com.example.permission.model.PermissionModel;
import com.example.permission.model.PermissionType;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Codex 019dd818 iter-14 (Plan A) regression coverage:
 * {@link PermissionDataInitializer#usesGranuleModel(Role)} detection.
 *
 * <p>V15 mixed-row cleanup migration başarısız olmuştu çünkü
 * {@code PermissionDataInitializer} aynı startup'ta granule role'lere
 * eski FK rows yeniden seedliyordu. Plan A: granule-aware skip — bu role'ler
 * için seed flow tamamen atlanır, V15/V16 cleanup kalıcı olur.
 *
 * <p>Bu suite üç senaryo'yu kilitler:
 *
 * <ul>
 *   <li><b>Granule role</b>: 1+ granule row varsa skip return true</li>
 *   <li><b>Mixed role</b>: granule + FK karışık olsa bile skip
 *       (incident state) — initializer bu durumda da seed yapmamalı</li>
 *   <li><b>Legacy FK-only role</b>: granule yok, legacy seed davranışı
 *       sürdürülür (backward compat)</li>
 * </ul>
 */
class PermissionDataInitializerGranuleAwareTest {

    @Test
    void usesGranuleModel_granuleOnlyRole_returnsTrue() {
        Role role = new Role();
        role.setName("GRANULE_ROLE");
        role.addRolePermission(granule(role, "USER_MANAGEMENT", GrantType.VIEW));

        assertThat(PermissionDataInitializer.usesGranuleModel(role)).isTrue();
    }

    @Test
    void usesGranuleModel_mixedRole_returnsTrue() {
        // Incident state: granule + FK rows side by side. Skip the role
        // entirely so V15/V16 cleanup can converge to granule-only.
        Role role = new Role();
        role.setName("MIXED_ROLE");
        role.addRolePermission(granule(role, "USER_MANAGEMENT", GrantType.VIEW));
        role.addRolePermission(legacyFk(role, "user-write", "USER_MANAGEMENT", GrantType.MANAGE));

        assertThat(PermissionDataInitializer.usesGranuleModel(role)).isTrue();
    }

    @Test
    void usesGranuleModel_legacyFkOnlyRole_returnsFalse() {
        // Backward compat: roller hâlâ FK-only ise initializer eski seed
        // davranışını sürdürmeli.
        Role role = new Role();
        role.setName("LEGACY_ROLE");
        role.addRolePermission(legacyFk(role, "user-write", "USER_MANAGEMENT", GrantType.MANAGE));
        role.addRolePermission(legacyFk(role, "user-read",  "USER_MANAGEMENT", GrantType.VIEW));

        assertThat(PermissionDataInitializer.usesGranuleModel(role)).isFalse();
    }

    @Test
    void usesGranuleModel_emptyRole_returnsFalse() {
        // Yeni oluşturulmuş role henüz hiçbir permission'a sahip değil; legacy
        // seed davranışı default olmalı (initializer FK rows ekleyebilir).
        Role role = new Role();
        role.setName("EMPTY_ROLE");

        assertThat(PermissionDataInitializer.usesGranuleModel(role)).isFalse();
    }

    /**
     * iter-16 (Plan C) regression: empty granule replace persists across boot.
     *
     * <p>Senaryo: Kullanıcı USER_MANAGE rolünün tüm modüllerini NONE yaptı
     * → AccessControllerV1.updateRoleGranules:
     *   - role.clearRolePermissions() → 0 row
     *   - role.setPermissionModel(GRANULE) → marker sticky
     *   - roleRepository.save(role)
     *
     * <p>Pod restart oldu → initializer DEFAULT_ROLE_PERMISSIONS seed
     * flow'una bakıyor. Marker GRANULE olduğu için row-shape predicate
     * false dönse bile usesGranuleModel true döner ve seed skip edilir.
     * Bu olmadan: empty role → row-shape false → legacy FK rows re-seed
     * → kullanıcının "hepsi NONE" save'i bir sonraki boot'ta kayboluyor.
     */
    @Test
    void usesGranuleModel_emptyButMarkedGranule_returnsTrue() {
        Role role = new Role();
        role.setName("USER_MANAGE_AFTER_EMPTY_REPLACE");
        role.setPermissionModel(PermissionModel.GRANULE);
        // Notice: rolePermissions intentionally empty.

        assertThat(PermissionDataInitializer.usesGranuleModel(role))
                .as("Marker alone must classify the role as granule-managed even with zero rows")
                .isTrue();
    }

    /**
     * iter-16 defensive predicate: marker LEGACY but row-shape evidences
     * granule data → marker-then-shape OR predicate still classifies the role
     * correctly. Bu yapılanma marker drift'inden korur (manuel SQL gibi
     * out-of-band marker düşürme senaryolarında bile correctness).
     */
    @Test
    void usesGranuleModel_legacyMarkerWithGranuleRow_returnsTrue() {
        Role role = new Role();
        role.setName("DRIFT_GUARD_ROLE");
        role.setPermissionModel(PermissionModel.LEGACY);
        role.addRolePermission(granule(role, "USER_MANAGEMENT", GrantType.VIEW));

        assertThat(PermissionDataInitializer.usesGranuleModel(role))
                .as("Row-shape evidence must still classify role as granule even if marker drifted")
                .isTrue();
    }

    @Test
    void usesGranuleModel_incompleteGranuleRow_returnsFalse() {
        // Defansif: type/key/grant'tan biri eksikse "granule değil" sayılır.
        // Bu durumda initializer normal seed flow'una düşer (kötü veri
        // bootstrap-time'da self-heal eder).
        Role role = new Role();
        role.setName("INCOMPLETE_ROLE");
        RolePermission incomplete = new RolePermission();
        incomplete.setRole(role);
        incomplete.setPermissionType(PermissionType.MODULE);
        // permissionKey + grantType set edilmedi
        role.getRolePermissions().add(incomplete);

        assertThat(PermissionDataInitializer.usesGranuleModel(role)).isFalse();
    }

    private RolePermission granule(Role role, String key, GrantType grant) {
        return new RolePermission(role, PermissionType.MODULE, key, grant);
    }

    private RolePermission legacyFk(Role role, String code, String moduleName, GrantType grant) {
        Permission p = new Permission();
        p.setCode(code);
        p.setModuleName(moduleName);
        RolePermission rp = new RolePermission(role, PermissionType.MODULE, moduleName, grant);
        rp.setPermission(p);
        return rp;
    }
}
