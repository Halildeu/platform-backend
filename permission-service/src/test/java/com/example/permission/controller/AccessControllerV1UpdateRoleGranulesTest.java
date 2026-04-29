package com.example.permission.controller;

import com.example.permission.dto.v1.RolePermissionItemDto;
import com.example.permission.event.RoleChangeEvent;
import com.example.permission.model.GrantType;
import com.example.permission.model.Permission;
import com.example.permission.model.PermissionModel;
import com.example.permission.model.PermissionType;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.service.AccessRoleService;
import com.example.permission.service.PermissionService;
import com.example.permission.service.TupleSyncService;
import com.example.permission.service.UserScopeService;
import com.example.permission.repository.UserRoleAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Codex 019dd818 iter-13 (Plan B) regression: AccessControllerV1.updateRoleGranules
 * aggregate-native replace flow.
 *
 * <p>Test eski "JPQL bulk delete + cascade save" pattern'inden Role
 * aggregate helper'lara (clearRolePermissions + addRolePermission) geçişi
 * davranışsal olarak doğrular:
 *
 * <ul>
 *   <li>Eski tüm RolePermission'lar kaldırılır (clearRolePermissions çağrılır
 *       VE Role.rolePermissions collection'u boşaltılır).</li>
 *   <li>Yeni granule'lar Role aggregate üzerinden eklenir (cascade=ALL ile
 *       JPA flush'te DB'ye yazılır).</li>
 *   <li>JPQL bulk delete (rolePermissionRepository.deleteByRoleId) ÇAĞRILMAZ
 *       — bu pattern persistence context'i bypass edip cascade-resurrect
 *       bug'ına yol açıyordu.</li>
 *   <li>Direct rolePermissionRepository.save(rp) ÇAĞRILMAZ — yeni rows
 *       aggregate üzerinden cascade-persist edilir.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AccessControllerV1UpdateRoleGranulesTest {

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

    /**
     * Plan B core regression: USER_MANAGE-style role with 3 legacy FK rows.
     * Save 1 granule (USER_MANAGEMENT/VIEW). Post-call invariants:
     *  - Role.rolePermissions size == 1 (eski 3 FK silinmiş, yeni 1 granule eklenmiş)
     *  - Yeni granule USER_MANAGEMENT/VIEW
     *  - rolePermissionRepository.deleteByRoleId() çağrılmadı (artık aggregate)
     *  - rolePermissionRepository.save(rp) çağrılmadı (artık aggregate)
     *  - roleRepository.save(role) çağrıldı (cascade-persist)
     *  - RoleChangeEvent publish edildi
     */
    @Test
    void updateRoleGranules_replacesLegacyFkRows_withSingleGranule() {
        Long roleId = 9L;
        Role role = new Role();
        role.setId(roleId);
        role.setName("USER_MANAGE");
        // Legacy state: 3 FK-bearing RolePermission (cascade-resurrect risk)
        role.addRolePermission(makeFkRow(role, "user-write", GrantType.MANAGE));
        role.addRolePermission(makeFkRow(role, "user-admin", GrantType.MANAGE));
        role.addRolePermission(makeFkRow(role, "user-read",  GrantType.VIEW));
        when(roleRepository.findByIdForUpdate(roleId)).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        // User saves: USER_MANAGEMENT/VIEW (single granule)
        Map<String, List<RolePermissionItemDto>> body = Map.of(
                "permissions", List.of(new RolePermissionItemDto("module", "USER_MANAGEMENT", "VIEW"))
        );

        ResponseEntity<Map<String, Object>> response = controller.updateRoleGranules(roleId, body);

        // --- ASSERT 1: response shape OK
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("roleId", roleId)
                .containsEntry("granuleCount", 1)
                .containsEntry("propagated", true);

        // --- ASSERT 2: legacy bulk-delete pattern artık YOK
        verify(rolePermissionRepository, never()).deleteByRoleId(any());
        verify(rolePermissionRepository, never()).save(any(RolePermission.class));

        // --- ASSERT 3: Role aggregate flow uygulandı
        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository).save(roleCaptor.capture());
        Role saved = roleCaptor.getValue();
        assertThat(saved.getRolePermissions())
                .as("Plan B: legacy FK rows clear + 1 new granule = exactly 1 entry")
                .hasSize(1);

        RolePermission only = saved.getRolePermissions().iterator().next();
        assertThat(only.getPermission()).as("New row is granule (no FK)").isNull();
        assertThat(only.getPermissionType()).isEqualTo(PermissionType.MODULE);
        assertThat(only.getPermissionKey()).isEqualTo("USER_MANAGEMENT");
        assertThat(only.getGrantType()).isEqualTo(GrantType.VIEW);
        assertThat(only.getRole()).as("Back-reference set by addRolePermission").isSameAs(saved);

        // --- ASSERT 4: iter-16 marker contract — saving granules flips the
        //     role to GRANULE so initializer skips it across boots.
        assertThat(saved.getPermissionModel())
                .as("iter-16: /granules call must mark role as GRANULE")
                .isEqualTo(PermissionModel.GRANULE);

        // --- ASSERT 5: change event published (CNS-002 contract)
        verify(eventPublisher).publishEvent(any(RoleChangeEvent.class));
    }

    /**
     * Empty replace: tüm modüller NONE seçildi → permissions:[] body.
     * Beklenen: role.rolePermissions tamamen boş (legacy FK rows orphan-removed).
     */
    @Test
    void updateRoleGranules_emptyPermissionsList_clearsAllRows() {
        Long roleId = 5L;
        Role role = new Role();
        role.setId(roleId);
        role.addRolePermission(makeFkRow(role, "system-configure", GrantType.MANAGE));
        when(roleRepository.findByIdForUpdate(roleId)).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, List<RolePermissionItemDto>> body = Map.of("permissions", List.of());
        ResponseEntity<Map<String, Object>> response = controller.updateRoleGranules(roleId, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository).save(roleCaptor.capture());
        Role saved = roleCaptor.getValue();
        assertThat(saved.getRolePermissions())
                .as("Empty replace must remove every existing row")
                .isEmpty();

        // iter-16 critical assertion: even when the new permission list is
        // empty, the role must still be marked GRANULE so the initializer
        // doesn't re-seed FK rows on the next boot. Without this assertion
        // the empty-replace boot regression is undetectable.
        assertThat(saved.getPermissionModel())
                .as("iter-16: empty /granules replace must STILL mark role as GRANULE")
                .isEqualTo(PermissionModel.GRANULE);

        verify(rolePermissionRepository, never()).deleteByRoleId(any());
    }

    /**
     * Multi-granule replace: 3 farklı modül save edildiğinde 3 granule kalır,
     * eski FK rows silinir.
     */
    @Test
    void updateRoleGranules_multipleGranules_replacesCleanly() {
        Long roleId = 14L;
        Role role = new Role();
        role.setId(roleId);
        role.addRolePermission(makeFkRow(role, "user-admin", GrantType.MANAGE));
        when(roleRepository.findByIdForUpdate(roleId)).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, List<RolePermissionItemDto>> body = Map.of("permissions", List.of(
                new RolePermissionItemDto("module", "USER_MANAGEMENT", "VIEW"),
                new RolePermissionItemDto("module", "ACCESS",          "MANAGE"),
                new RolePermissionItemDto("module", "REPORT",          "VIEW")
        ));

        ResponseEntity<Map<String, Object>> response = controller.updateRoleGranules(roleId, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().get("granuleCount")).isEqualTo(3);

        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository).save(roleCaptor.capture());
        Role saved = roleCaptor.getValue();
        assertThat(saved.getRolePermissions()).hasSize(3);
        assertThat(saved.getRolePermissions().stream().map(RolePermission::getPermissionKey))
                .containsExactlyInAnyOrder("USER_MANAGEMENT", "ACCESS", "REPORT");
        assertThat(saved.getRolePermissions().stream().allMatch(rp -> rp.getPermission() == null))
                .as("All new rows must be granules (no legacy FK)")
                .isTrue();
    }

    // ------------------------------------------------------------------------
    // Codex 019dd9f0 iter-22 (A++ hotfix) regression suite. These guards target
    // the specific failure modes flagged in the post-impl review:
    //   - Hibernate flush ordering on the partial unique index
    //     uk_role_permissions_role_granule (live HTTP 500 reported on
    //     2026-04-29).
    //   - Duplicate (type, key) payload entries silently producing the same
    //     500 because RolePermission has no business-key equals/hashCode.
    //   - Concurrent /granules call serialization at the parent role row.
    // ------------------------------------------------------------------------

    /**
     * iter-22 critical: Hibernate flush ordering. The endpoint must explicitly
     * flush pending orphan-removal DELETEs to the database BEFORE the new
     * granule INSERTs are queued. Otherwise overlapping (role_id, type, key)
     * tuples crash on the partial unique index.
     */
    @Test
    void updateRoleGranules_flushesAfterClear_beforeNewInserts() {
        Long roleId = 11L;
        Role role = new Role();
        role.setId(roleId);
        role.addRolePermission(new RolePermission(role, PermissionType.MODULE, "USER_MANAGEMENT", GrantType.MANAGE));
        when(roleRepository.findByIdForUpdate(roleId)).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        // Same key, different grant: this is the exact production scenario that
        // reproduced the partial-unique-index 500 (USER_MANAGEMENT MANAGE → VIEW).
        Map<String, List<RolePermissionItemDto>> body = Map.of(
                "permissions", List.of(new RolePermissionItemDto("module", "USER_MANAGEMENT", "VIEW"))
        );

        ResponseEntity<Map<String, Object>> response = controller.updateRoleGranules(roleId, body);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        // Critical invariant: roleRepository.flush() must be invoked AFTER the
        // aggregate clear and BEFORE roleRepository.save(...) so that
        // orphanRemoval DELETEs reach Postgres before the new INSERTs.
        InOrder inOrder = inOrder(roleRepository);
        inOrder.verify(roleRepository).findByIdForUpdate(roleId);
        inOrder.verify(roleRepository).flush();
        inOrder.verify(roleRepository).save(any(Role.class));

        // Final state: single granule with the new grant.
        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository).save(captor.capture());
        Role saved = captor.getValue();
        assertThat(saved.getRolePermissions()).hasSize(1);
        RolePermission only = saved.getRolePermissions().iterator().next();
        assertThat(only.getPermissionKey()).isEqualTo("USER_MANAGEMENT");
        assertThat(only.getGrantType()).isEqualTo(GrantType.VIEW);
    }

    /**
     * iter-22 critical: duplicate (type, key) entries in payload must be
     * rejected with 400, not silently accepted (which would later crash with
     * a 500 "Beklenmeyen bir hata oluştu" from the constraint violation).
     */
    @Test
    void updateRoleGranules_duplicateTypeKey_returns400() {
        Long roleId = 12L;
        Role role = new Role();
        role.setId(roleId);
        when(roleRepository.findByIdForUpdate(roleId)).thenReturn(Optional.of(role));

        Map<String, List<RolePermissionItemDto>> body = Map.of("permissions", List.of(
                new RolePermissionItemDto("module", "USER_MANAGEMENT", "VIEW"),
                new RolePermissionItemDto("module", "USER_MANAGEMENT", "MANAGE")  // duplicate
        ));

        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.updateRoleGranules(roleId, body));
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
        assertThat(ex.getReason()).contains("Duplicate granule")
                .contains("USER_MANAGEMENT");

        // No mutation must reach the database when validation fails.
        verify(roleRepository, never()).save(any(Role.class));
        verify(roleRepository, never()).flush();
        verify(eventPublisher, never()).publishEvent(any(RoleChangeEvent.class));
    }

    /**
     * iter-22: malformed payload (null/blank type/key/grant) returns 400
     * instead of crashing on the {@code valueOf} call.
     */
    @Test
    void updateRoleGranules_blankFields_returns400() {
        Long roleId = 15L;
        Role role = new Role();
        role.setId(roleId);
        when(roleRepository.findByIdForUpdate(roleId)).thenReturn(Optional.of(role));

        Map<String, List<RolePermissionItemDto>> body = Map.of("permissions", List.of(
                new RolePermissionItemDto("module", "", "VIEW")  // blank key
        ));

        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.updateRoleGranules(roleId, body));
        assertThat(ex.getStatusCode().value()).isEqualTo(400);
        verify(roleRepository, never()).save(any(Role.class));
    }

    /**
     * iter-22: case-insensitive type/grant. Frontend sends "module" / "view"
     * (lowercase); the endpoint must canonicalize before
     * {@link PermissionType#valueOf(String)} is called.
     */
    @Test
    void updateRoleGranules_lowercaseTypeAndGrant_canonicalized() {
        Long roleId = 16L;
        Role role = new Role();
        role.setId(roleId);
        when(roleRepository.findByIdForUpdate(roleId)).thenReturn(Optional.of(role));
        when(roleRepository.save(any(Role.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, List<RolePermissionItemDto>> body = Map.of("permissions", List.of(
                new RolePermissionItemDto("module", "ACCESS", "manage")  // lowercase grant
        ));

        ResponseEntity<Map<String, Object>> response = controller.updateRoleGranules(roleId, body);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository).save(captor.capture());
        RolePermission only = captor.getValue().getRolePermissions().iterator().next();
        assertThat(only.getPermissionType()).isEqualTo(PermissionType.MODULE);
        assertThat(only.getGrantType()).isEqualTo(GrantType.MANAGE);
    }

    private RolePermission makeFkRow(Role role, String code, GrantType grant) {
        Permission p = new Permission();
        p.setCode(code);
        p.setModuleName("USER_MANAGEMENT");
        RolePermission rp = new RolePermission(role, PermissionType.MODULE, "USER_MANAGEMENT", grant);
        rp.setPermission(p);
        return rp;
    }
}
