package com.example.permission.controller;

import com.example.permission.dto.v1.RoleGranulesDto;
import com.example.permission.dto.v1.RolePermissionItemDto;
import com.example.permission.model.GrantType;
import com.example.permission.model.Permission;
import com.example.permission.model.PermissionType;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Codex 019dda05 iter-25: read-after-write regression suite for the role
 * drawer. Validates the typed read endpoint
 * {@code GET /api/v1/roles/{roleId}/granules} that drives mfe-access drawer
 * REPORT/ACTION selects.
 *
 * <p>Pre-iter-25 the drawer queried {@code GET /v1/roles/{id}} and parsed
 * {@code policies} (module summary). REPORT/ACTION granules were not in the
 * response, so save round-trip showed "Yetki Yok" even after a successful
 * PUT. The new endpoint exposes every granule-shape row in typed form.
 */
@ExtendWith(MockitoExtension.class)
class AccessControllerV1GetRoleGranulesTest {

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
     * Core read-after-write contract: every granule row written by
     * {@code PUT /granules} comes back through {@code GET /granules}
     * in typed shape ({@code {type, key, grant}}).
     */
    @Test
    void getRoleGranules_returnsAllGranuleRows_typedShape() {
        Long roleId = 13L;
        Role role = new Role();
        role.setId(roleId);
        role.setName("REPORT_MANAGER");
        role.addRolePermission(new RolePermission(role, PermissionType.MODULE, "USER_MANAGEMENT", GrantType.VIEW));
        role.addRolePermission(new RolePermission(role, PermissionType.MODULE, "REPORT", GrantType.MANAGE));
        role.addRolePermission(new RolePermission(role, PermissionType.REPORT, "HR_REPORTS", GrantType.VIEW));
        role.addRolePermission(new RolePermission(role, PermissionType.REPORT, "FINANCE_REPORTS", GrantType.MANAGE));
        role.addRolePermission(new RolePermission(role, PermissionType.ACTION, "DELETE_PO", GrantType.ALLOW));
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        ResponseEntity<RoleGranulesDto> response = controller.getRoleGranules(roleId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        RoleGranulesDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.roleId()).isEqualTo(roleId);
        assertThat(dto.granules()).hasSize(5);
        assertThat(dto.granules())
                .extracting(RolePermissionItemDto::type, RolePermissionItemDto::key,
                            RolePermissionItemDto::grant)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("MODULE", "USER_MANAGEMENT", "VIEW"),
                        org.assertj.core.groups.Tuple.tuple("MODULE", "REPORT",          "MANAGE"),
                        org.assertj.core.groups.Tuple.tuple("REPORT", "HR_REPORTS",      "VIEW"),
                        org.assertj.core.groups.Tuple.tuple("REPORT", "FINANCE_REPORTS", "MANAGE"),
                        org.assertj.core.groups.Tuple.tuple("ACTION", "DELETE_PO",       "ALLOW")
                );
    }

    /**
     * Deterministic sort: MODULE → ACTION → REPORT, then alphabetic key.
     * Drawer UI relies on stable order so module groups don't flicker
     * across re-fetches.
     */
    @Test
    void getRoleGranules_sortsTypeThenKey_deterministic() {
        Long roleId = 7L;
        Role role = new Role();
        role.setId(roleId);
        // Insertion order intentionally jumbled — output must still be sorted.
        role.addRolePermission(new RolePermission(role, PermissionType.REPORT, "Z_REPORT", GrantType.VIEW));
        role.addRolePermission(new RolePermission(role, PermissionType.MODULE, "A_MODULE", GrantType.MANAGE));
        role.addRolePermission(new RolePermission(role, PermissionType.ACTION, "M_ACTION", GrantType.ALLOW));
        role.addRolePermission(new RolePermission(role, PermissionType.REPORT, "A_REPORT", GrantType.MANAGE));
        role.addRolePermission(new RolePermission(role, PermissionType.MODULE, "Z_MODULE", GrantType.VIEW));
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        ResponseEntity<RoleGranulesDto> response = controller.getRoleGranules(roleId);
        List<RolePermissionItemDto> granules = response.getBody().granules();

        assertThat(granules)
                .extracting(RolePermissionItemDto::type, RolePermissionItemDto::key)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("MODULE", "A_MODULE"),
                        org.assertj.core.groups.Tuple.tuple("MODULE", "Z_MODULE"),
                        org.assertj.core.groups.Tuple.tuple("ACTION", "M_ACTION"),
                        org.assertj.core.groups.Tuple.tuple("REPORT", "A_REPORT"),
                        org.assertj.core.groups.Tuple.tuple("REPORT", "Z_REPORT")
                );
    }

    /**
     * Legacy FK rows ({@code permission_id IS NOT NULL}) must NOT appear
     * in the granules response. They remain exposed only through
     * {@code AccessRoleDto.permissions}.
     */
    @Test
    void getRoleGranules_excludesLegacyFkRows() {
        Long roleId = 11L;
        Role role = new Role();
        role.setId(roleId);
        // 1 granule row + 1 legacy FK row
        role.addRolePermission(new RolePermission(role, PermissionType.MODULE, "ACCESS", GrantType.MANAGE));
        Permission legacyFk = new Permission();
        legacyFk.setCode("user-admin");
        legacyFk.setModuleName("USER_MANAGEMENT");
        RolePermission legacyRow = new RolePermission(role, PermissionType.MODULE, "USER_MANAGEMENT", GrantType.MANAGE);
        legacyRow.setPermission(legacyFk);
        role.addRolePermission(legacyRow);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        ResponseEntity<RoleGranulesDto> response = controller.getRoleGranules(roleId);
        List<RolePermissionItemDto> granules = response.getBody().granules();

        assertThat(granules).hasSize(1);
        assertThat(granules.get(0).key()).isEqualTo("ACCESS");
    }

    /**
     * Empty role → empty granules list (200, not 404). The drawer
     * uses this to render an empty-but-functional state for newly-
     * created roles.
     */
    @Test
    void getRoleGranules_emptyRole_returns200WithEmptyList() {
        Long roleId = 99L;
        Role role = new Role();
        role.setId(roleId);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        ResponseEntity<RoleGranulesDto> response = controller.getRoleGranules(roleId);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().granules()).isEmpty();
    }

    /**
     * Non-existent role → 404 ResponseStatusException.
     */
    @Test
    void getRoleGranules_unknownRole_returns404() {
        Long roleId = 999L;
        when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.getRoleGranules(roleId));
        assertThat(ex.getStatusCode().value()).isEqualTo(404);
    }

    /**
     * Skips rows with null type/key/grant (defensive — should never
     * happen but the database column is nullable on legacy rows).
     */
    @Test
    void getRoleGranules_skipsMalformedRows() {
        Long roleId = 22L;
        Role role = new Role();
        role.setId(roleId);
        role.addRolePermission(new RolePermission(role, PermissionType.MODULE, "OK", GrantType.VIEW));
        // Malformed row: blank key — must be filtered out.
        RolePermission blankKey = new RolePermission(role, PermissionType.REPORT, "", GrantType.VIEW);
        role.addRolePermission(blankKey);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        ResponseEntity<RoleGranulesDto> response = controller.getRoleGranules(roleId);
        List<RolePermissionItemDto> granules = response.getBody().granules();

        assertThat(granules).hasSize(1);
        assertThat(granules.get(0).key()).isEqualTo("OK");
    }
}
