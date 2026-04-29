package com.example.permission.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.scope.ScopeContextHolder;
import com.example.permission.model.Role;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.service.AuditEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Codex 019dda1c iter-33 — Super-admin grant/revoke endpoints.
 *
 * <p>Authorization model: <b>self-replicating equals-or-higher</b>. Only
 * existing super-admins may grant or revoke super-admin status. This blocks
 * privilege escalation through the ACCESS module ({@code can_manage} on
 * ACCESS would otherwise let an access manager promote themselves above
 * their own scope).
 *
 * <p>Atomic two-phase operation:
 * <ul>
 *   <li>DB: {@link UserRoleAssignment} insert/deactivate (ADMIN role)</li>
 *   <li>OpenFGA: tuple write/delete ({@code organization:default#admin@user:&lt;id&gt;})</li>
 * </ul>
 * The OpenFGA call runs INSIDE the {@code @Transactional} method; if it
 * throws, the DB change rolls back.
 *
 * <p>Last-admin protection: revoke returns 409 if it would leave zero
 * active super-admins. Self-removal is allowed when at least one other
 * super-admin remains.
 *
 * <p>Bootstrap awareness: when the target user's email is in the
 * {@code permission.bootstrap.default-admin-assignments.admin-emails}
 * list, the response includes a warning that the assignment will be
 * re-asserted on the next pod restart unless the bootstrap config is
 * also updated.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class SuperAdminControllerV1 {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminControllerV1.class);
    private static final String ADMIN_ROLE_NAME = "ADMIN";
    private static final String FGA_RELATION = "admin";
    private static final String FGA_OBJECT_TYPE = "organization";
    private static final String FGA_OBJECT_ID = "default";
    private static final Pattern QUALIFIED_TABLE_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final RoleRepository roleRepository;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final OpenFgaAuthzService openFgaAuthzService;
    private final AuditEventService auditEventService;
    private final JdbcTemplate jdbcTemplate;
    private final List<String> bootstrapAdminEmails;
    private final String userTable;

    public SuperAdminControllerV1(
            RoleRepository roleRepository,
            UserRoleAssignmentRepository assignmentRepository,
            @Nullable OpenFgaAuthzService openFgaAuthzService,
            AuditEventService auditEventService,
            JdbcTemplate jdbcTemplate,
            @Value("${permission.bootstrap.default-admin-assignments.admin-emails:}") String bootstrapAdminEmails,
            @Value("${permission.bootstrap.default-admin-assignments.user-table:users}") String userTable) {
        this.roleRepository = roleRepository;
        this.assignmentRepository = assignmentRepository;
        this.openFgaAuthzService = openFgaAuthzService;
        this.auditEventService = auditEventService;
        this.jdbcTemplate = jdbcTemplate;
        this.bootstrapAdminEmails = parseEmails(bootstrapAdminEmails);
        this.userTable = normalizeTableName(userTable);
    }

    @Transactional
    @PostMapping("/{userId}/super-admin")
    public ResponseEntity<Map<String, Object>> grantSuperAdmin(@PathVariable("userId") Long userId) {
        requireCallerSuperAdmin();
        Long callerUserId = parseCallerUserId();

        Role adminRole = adminRoleOrServiceUnavailable();

        Optional<UserRoleAssignment> existing =
                assignmentRepository.findActiveAssignment(userId, null, adminRole.getId(), null, null);
        boolean alreadyHadGrant = existing.isPresent();

        if (!alreadyHadGrant) {
            UserRoleAssignment assignment = new UserRoleAssignment();
            assignment.setUserId(userId);
            assignment.setRole(adminRole);
            assignment.setActive(true);
            assignment.setAssignedAt(Instant.now());
            assignment.setAssignedBy(callerUserId);
            assignmentRepository.save(assignment);
        }

        // OpenFGA tuple write — idempotent; failure rolls back the DB insert
        // because we are inside the @Transactional method and rethrow.
        if (openFgaAuthzService != null) {
            try {
                openFgaAuthzService.writeTuple(
                        String.valueOf(userId), FGA_RELATION, FGA_OBJECT_TYPE, FGA_OBJECT_ID);
            } catch (RuntimeException ex) {
                log.error("Super-admin grant: OpenFGA tuple write failed for userId={}; rolling back DB.",
                        userId, ex);
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "OpenFGA write failed", ex);
            }
        }

        auditEventService.recordEvent(auditEventService.buildEvent(
                "SUPER_ADMIN_GRANTED",
                callerUserId,
                "Super-admin role granted",
                userId,
                "INFO",
                "GRANT",
                Map.of("roleName", ADMIN_ROLE_NAME, "fgaRelation", FGA_RELATION,
                        "alreadyHadGrant", alreadyHadGrant),
                null,
                Map.of("userId", userId, "active", true)));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId);
        response.put("granted", true);
        response.put("alreadyHadGrant", alreadyHadGrant);
        addBootstrapWarning(userId, response);
        return ResponseEntity.ok(response);
    }

    @Transactional
    @DeleteMapping("/{userId}/super-admin")
    public ResponseEntity<Map<String, Object>> revokeSuperAdmin(@PathVariable("userId") Long userId) {
        requireCallerSuperAdmin();
        Long callerUserId = parseCallerUserId();

        Role adminRole = adminRoleOrServiceUnavailable();

        // Last-admin guard: count active ADMIN assignments. If the target
        // currently holds the role and is the only one, refuse — otherwise
        // the system would have zero super-admins and only the boot-time
        // initializer could restore admin access.
        long activeBefore = assignmentRepository.countByRoleAndActiveTrue(adminRole);
        Optional<UserRoleAssignment> existing =
                assignmentRepository.findActiveAssignment(userId, null, adminRole.getId(), null, null);
        boolean targetIsCurrentSuperAdmin = existing.isPresent();

        if (targetIsCurrentSuperAdmin && activeBefore <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "last-admin protection: cannot revoke the only active super-admin "
                            + "(activeCount=" + activeBefore + ")");
        }

        if (targetIsCurrentSuperAdmin) {
            UserRoleAssignment assignment = existing.get();
            assignment.setActive(false);
            assignment.setRevokedAt(Instant.now());
            assignment.setRevokedBy(callerUserId);
            assignmentRepository.save(assignment);
        }

        // OpenFGA tuple delete — idempotent; failure rolls back the DB
        // change via the @Transactional rethrow.
        if (openFgaAuthzService != null) {
            try {
                openFgaAuthzService.deleteTuple(
                        String.valueOf(userId), FGA_RELATION, FGA_OBJECT_TYPE, FGA_OBJECT_ID);
            } catch (RuntimeException ex) {
                log.error("Super-admin revoke: OpenFGA tuple delete failed for userId={}; rolling back DB.",
                        userId, ex);
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "OpenFGA delete failed", ex);
            }
        }

        long remaining = targetIsCurrentSuperAdmin ? activeBefore - 1 : activeBefore;
        auditEventService.recordEvent(auditEventService.buildEvent(
                "SUPER_ADMIN_REVOKED",
                callerUserId,
                "Super-admin role revoked",
                userId,
                "WARN",
                "REVOKE",
                Map.of("roleName", ADMIN_ROLE_NAME, "fgaRelation", FGA_RELATION,
                        "remainingActiveAdmins", remaining,
                        "targetHadActiveGrant", targetIsCurrentSuperAdmin),
                Map.of("userId", userId, "active", true),
                Map.of("userId", userId, "active", false)));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId);
        response.put("revoked", true);
        response.put("hadActiveGrant", targetIsCurrentSuperAdmin);
        response.put("remainingActiveAdmins", remaining);
        addBootstrapWarning(userId, response);
        return ResponseEntity.ok(response);
    }

    private void requireCallerSuperAdmin() {
        var ctx = ScopeContextHolder.get();
        if (ctx == null || !ctx.superAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "super-admin authorization required");
        }
    }

    @Nullable
    private Long parseCallerUserId() {
        var ctx = ScopeContextHolder.get();
        if (ctx == null || ctx.userId() == null) return null;
        try {
            return Long.parseLong(ctx.userId());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Role adminRoleOrServiceUnavailable() {
        return roleRepository.findByNameIgnoreCase(ADMIN_ROLE_NAME).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "ADMIN role missing — bootstrap incomplete"));
    }

    private void addBootstrapWarning(Long userId, Map<String, Object> response) {
        if (bootstrapAdminEmails.isEmpty()) return;
        try {
            String email = lookupEmailById(userId);
            if (email != null && bootstrapAdminEmails.contains(email.toLowerCase(Locale.ROOT))) {
                response.put("bootstrapWarning",
                        "Email '" + email + "' is in permission.bootstrap.default-admin-assignments.admin-emails — "
                                + "super-admin status will be re-asserted at next pod restart unless the "
                                + "bootstrap config is also updated.");
            }
        } catch (DataAccessException ex) {
            // Email lookup is best-effort; never block the grant/revoke.
            log.debug("Bootstrap warning lookup skipped (table missing or other DB error). userId={} reason={}",
                    userId, ex.getClass().getSimpleName());
        }
    }

    @Nullable
    private String lookupEmailById(Long userId) {
        if (userId == null) return null;
        String sql = "select email from " + userTable + " where id = ?";
        List<String> rows = jdbcTemplate.queryForList(sql, String.class, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<String> parseEmails(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableList());
    }

    private static String normalizeTableName(@Nullable String value) {
        if (value == null || value.isBlank()) return "users";
        String trimmed = value.trim();
        if (!QUALIFIED_TABLE_NAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid user table name: " + value);
        }
        return trimmed;
    }
}
