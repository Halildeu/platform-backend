package com.example.permission.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.scope.ScopeContext;
import com.example.commonauth.scope.ScopeContextHolder;
import com.example.permission.model.PermissionAuditEvent;
import com.example.permission.model.Role;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.service.AuditEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Codex 019dda1c iter-33 — SuperAdminControllerV1 unit tests covering the
 * authorization model and the last-admin guard.
 *
 * <p>Direct controller invocation (not WebMvcTest) so we can manipulate
 * {@link ScopeContextHolder} thread-local directly — that's the actual
 * authorization source for the controller under test.
 */
class SuperAdminControllerV1Test {

    private RoleRepository roleRepository;
    private UserRoleAssignmentRepository assignmentRepository;
    private OpenFgaAuthzService openFgaAuthzService;
    private AuditEventService auditEventService;
    private JdbcTemplate jdbcTemplate;
    private SuperAdminControllerV1 controller;

    private Role adminRole;

    @BeforeEach
    void setUp() {
        roleRepository = mock(RoleRepository.class);
        assignmentRepository = mock(UserRoleAssignmentRepository.class);
        openFgaAuthzService = mock(OpenFgaAuthzService.class);
        auditEventService = mock(AuditEventService.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        adminRole = new Role();
        adminRole.setId(1L);
        adminRole.setName("ADMIN");
        when(roleRepository.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(adminRole));

        // recordEvent + buildEvent must return a non-null PermissionAuditEvent so
        // chained calls compile; we don't assert on the specific instance here.
        PermissionAuditEvent stubEvent = new PermissionAuditEvent();
        when(auditEventService.buildEvent(anyString(), any(), anyString(), any(), anyString(), anyString(),
                any(), any(), any())).thenReturn(stubEvent);
        when(auditEventService.recordEvent(any())).thenReturn(stubEvent);

        controller = new SuperAdminControllerV1(
                roleRepository,
                assignmentRepository,
                openFgaAuthzService,
                auditEventService,
                jdbcTemplate,
                "",
                "users");
    }

    @AfterEach
    void tearDown() {
        ScopeContextHolder.clear();
    }

    private void asSuperAdmin(String callerUserId) {
        ScopeContextHolder.set(ScopeContext.superAdmin(callerUserId));
    }

    private void asNonSuperAdmin(String callerUserId) {
        ScopeContextHolder.set(ScopeContext.empty(callerUserId));
    }

    // ---------- GRANT ----------

    @Test
    void grant_happy_path_writes_db_and_fga_and_audit() {
        asSuperAdmin("100");
        when(assignmentRepository.findActiveAssignment(eq(42L), any(), eq(1L), any(), any()))
                .thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.grantSuperAdmin(42L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(true, resp.getBody().get("granted"));
        assertEquals(false, resp.getBody().get("alreadyHadGrant"));
        verify(assignmentRepository, times(1)).save(any(UserRoleAssignment.class));
        verify(openFgaAuthzService, times(1)).writeTuple("42", "admin", "organization", "default");
        verify(auditEventService, times(1)).recordEvent(any());
    }

    @Test
    void grant_idempotent_when_already_assigned_skips_db_insert_but_still_writes_fga() {
        asSuperAdmin("100");
        UserRoleAssignment existing = new UserRoleAssignment();
        existing.setUserId(42L);
        existing.setRole(adminRole);
        existing.setActive(true);
        when(assignmentRepository.findActiveAssignment(eq(42L), any(), eq(1L), any(), any()))
                .thenReturn(Optional.of(existing));

        ResponseEntity<Map<String, Object>> resp = controller.grantSuperAdmin(42L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("alreadyHadGrant"));
        verify(assignmentRepository, never()).save(any(UserRoleAssignment.class));
        verify(openFgaAuthzService, times(1)).writeTuple("42", "admin", "organization", "default");
    }

    @Test
    void grant_denied_for_non_super_admin_caller() {
        asNonSuperAdmin("99");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.grantSuperAdmin(42L));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(assignmentRepository, never()).save(any(UserRoleAssignment.class));
        verify(openFgaAuthzService, never()).writeTuple(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void grant_denied_when_no_scope_context() {
        // ScopeContextHolder not set — anonymous request
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.grantSuperAdmin(42L));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void grant_propagates_openfga_failure_so_transactional_rollback_kicks_in() {
        asSuperAdmin("100");
        when(assignmentRepository.findActiveAssignment(eq(42L), any(), eq(1L), any(), any()))
                .thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("fga down"))
                .when(openFgaAuthzService).writeTuple(anyString(), anyString(), anyString(), anyString());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.grantSuperAdmin(42L));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
    }

    // ---------- REVOKE ----------

    @Test
    void revoke_happy_path_when_other_admins_remain() {
        asSuperAdmin("100");
        UserRoleAssignment existing = new UserRoleAssignment();
        existing.setUserId(42L);
        existing.setRole(adminRole);
        existing.setActive(true);
        when(assignmentRepository.findActiveAssignment(eq(42L), any(), eq(1L), any(), any()))
                .thenReturn(Optional.of(existing));
        when(assignmentRepository.countByRoleAndActiveTrue(adminRole)).thenReturn(2L);

        ResponseEntity<Map<String, Object>> resp = controller.revokeSuperAdmin(42L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(true, resp.getBody().get("revoked"));
        assertEquals(true, resp.getBody().get("hadActiveGrant"));
        assertEquals(1L, resp.getBody().get("remainingActiveAdmins"));
        verify(assignmentRepository, times(1)).save(any(UserRoleAssignment.class));
        verify(openFgaAuthzService, times(1)).deleteTuple("42", "admin", "organization", "default");
    }

    @Test
    void revoke_blocks_last_admin_with_409() {
        asSuperAdmin("100");
        UserRoleAssignment existing = new UserRoleAssignment();
        existing.setUserId(42L);
        existing.setRole(adminRole);
        existing.setActive(true);
        when(assignmentRepository.findActiveAssignment(eq(42L), any(), eq(1L), any(), any()))
                .thenReturn(Optional.of(existing));
        when(assignmentRepository.countByRoleAndActiveTrue(adminRole)).thenReturn(1L);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.revokeSuperAdmin(42L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("last-admin protection"));
        verify(assignmentRepository, never()).save(any(UserRoleAssignment.class));
        verify(openFgaAuthzService, never()).deleteTuple(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void revoke_succeeds_when_target_is_not_currently_super_admin_and_count_is_safe() {
        // Idempotent revoke: target wasn't admin to begin with — still
        // perform the OpenFGA delete (idempotent) but skip the DB update.
        asSuperAdmin("100");
        when(assignmentRepository.findActiveAssignment(eq(42L), any(), eq(1L), any(), any()))
                .thenReturn(Optional.empty());
        when(assignmentRepository.countByRoleAndActiveTrue(adminRole)).thenReturn(2L);

        ResponseEntity<Map<String, Object>> resp = controller.revokeSuperAdmin(42L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(false, resp.getBody().get("hadActiveGrant"));
        verify(assignmentRepository, never()).save(any(UserRoleAssignment.class));
        verify(openFgaAuthzService, times(1)).deleteTuple("42", "admin", "organization", "default");
    }

    @Test
    void revoke_denied_for_non_super_admin_caller() {
        asNonSuperAdmin("99");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.revokeSuperAdmin(42L));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(assignmentRepository, never()).save(any(UserRoleAssignment.class));
        verify(openFgaAuthzService, never()).deleteTuple(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void revoke_self_allowed_when_others_exist() {
        // Caller userId 100, target userId 100 — same person. Last-admin
        // guard says count=2, so self-removal is permitted.
        asSuperAdmin("100");
        UserRoleAssignment existing = new UserRoleAssignment();
        existing.setUserId(100L);
        existing.setRole(adminRole);
        existing.setActive(true);
        when(assignmentRepository.findActiveAssignment(eq(100L), any(), eq(1L), any(), any()))
                .thenReturn(Optional.of(existing));
        when(assignmentRepository.countByRoleAndActiveTrue(adminRole)).thenReturn(2L);

        ResponseEntity<Map<String, Object>> resp = controller.revokeSuperAdmin(100L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1L, resp.getBody().get("remainingActiveAdmins"));
    }

    @Test
    void revoke_propagates_openfga_failure_so_transactional_rollback_kicks_in() {
        asSuperAdmin("100");
        UserRoleAssignment existing = new UserRoleAssignment();
        existing.setUserId(42L);
        existing.setRole(adminRole);
        existing.setActive(true);
        when(assignmentRepository.findActiveAssignment(eq(42L), any(), eq(1L), any(), any()))
                .thenReturn(Optional.of(existing));
        when(assignmentRepository.countByRoleAndActiveTrue(adminRole)).thenReturn(2L);
        org.mockito.Mockito.doThrow(new RuntimeException("fga down"))
                .when(openFgaAuthzService).deleteTuple(anyString(), anyString(), anyString(), anyString());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.revokeSuperAdmin(42L));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
    }

    // ---------- BOOTSTRAP WARNING ----------

    @Test
    void grant_includes_bootstrap_warning_when_email_is_in_bootstrap_list() {
        // Re-instantiate controller with bootstrap emails configured.
        controller = new SuperAdminControllerV1(
                roleRepository,
                assignmentRepository,
                openFgaAuthzService,
                auditEventService,
                jdbcTemplate,
                "admin@example.com,bootstrap@example.com",
                "users");

        asSuperAdmin("100");
        when(assignmentRepository.findActiveAssignment(eq(42L), any(), eq(1L), any(), any()))
                .thenReturn(Optional.empty());
        when(jdbcTemplate.queryForList("select email from users where id = ?", String.class, 42L))
                .thenReturn(java.util.List.of("admin@example.com"));

        ResponseEntity<Map<String, Object>> resp = controller.grantSuperAdmin(42L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody().get("bootstrapWarning"));
        assertTrue(((String) resp.getBody().get("bootstrapWarning"))
                .contains("admin@example.com"));
    }

    @Test
    void grant_omits_bootstrap_warning_when_email_not_in_list() {
        controller = new SuperAdminControllerV1(
                roleRepository,
                assignmentRepository,
                openFgaAuthzService,
                auditEventService,
                jdbcTemplate,
                "bootstrap@example.com",
                "users");

        asSuperAdmin("100");
        when(assignmentRepository.findActiveAssignment(eq(42L), any(), eq(1L), any(), any()))
                .thenReturn(Optional.empty());
        when(jdbcTemplate.queryForList("select email from users where id = ?", String.class, 42L))
                .thenReturn(java.util.List.of("user@example.com"));

        ResponseEntity<Map<String, Object>> resp = controller.grantSuperAdmin(42L);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNull(resp.getBody().get("bootstrapWarning"));
    }
}
