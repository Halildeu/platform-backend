package com.example.permission.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.permission.model.Role;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Faz 35 — this bootstrap must not grant across an unverified id space (#971).
 *
 * <p>It turns an email into a number and gives ADMIN to that number. The number only means
 * something if the table it came from is the same id space authorization resolves in. On the
 * live cell it was not: the lookup ran against {@code permission_db.users} while every
 * authorization decision resolves through the user directory. {@code admin@example.com} was
 * id 1 in one table and a different person was id 1 in the other, so ADMIN — together with
 * {@code organization:default#admin} — landed on somebody nobody had chosen.
 *
 * <p>What makes it worth a guard rather than a fix-and-move-on is that it healed itself in
 * the wrong direction: the bootstrap re-ran on every pod start, so any manual cleanup was
 * undone by the next restart.
 *
 * <p>These tests are about refusal, which is the part that has no visible symptom. A wrong
 * grant looks exactly like a right one until someone reads the two tables side by side.
 */
class DefaultAdminRoleAssignmentIdSpaceTest {

    private static final List<String> EMAILS = List.of("admin@example.com");

    private DefaultAdminRoleAssignmentInitializer initializer(
            String idSpace, JdbcTemplate jdbc, RoleRepository roles, UserRoleAssignmentRepository assignments) {
        return new DefaultAdminRoleAssignmentInitializer(
                jdbc, roles, assignments, true, String.join(",", EMAILS), 1, 0L, "users", idSpace, null);
    }

    @Test
    @DisplayName("id uzayı doğrulanmadıysa hiçbir atama yapılmaz ve tabloya bakılmaz")
    void refusesWhenTheIdSpaceIsNotDeclaredCanonical() {
        var jdbc = mock(JdbcTemplate.class);
        var roles = mock(RoleRepository.class);
        var assignments = mock(UserRoleAssignmentRepository.class);

        initializer("unverified", jdbc, roles, assignments).run();

        // Sorgu hiç çalışmamalı: yanlış tabloya bakıp "bulamadım" demek yetmez, bakmamalı.
        verifyNoInteractions(jdbc);
        verifyNoInteractions(assignments);
        verify(roles, never()).findByNameIgnoreCase(anyString());
    }

    /**
     * Asserts on the role lookup, not only on the query. With the gate removed the run
     * proceeds, finds no ADMIN role on a bare mock, retries once and returns — so
     * "the table was never queried" holds for the wrong reason and the test would stay
     * green against the very defect it exists to catch. The role lookup is the first thing
     * past the gate, which makes it the honest tripwire.
     */
    @Test
    @DisplayName("boş ya da yazım hatalı beyan da reddedilir — varsayılan izin vermek değildir")
    void anyDeclarationOtherThanCanonicalRefuses() {
        for (String declared : List.of("", "  ", "canonik", "CANONICAL_ISH", "true", "users_db")) {
            var jdbc = mock(JdbcTemplate.class);
            var roles = mock(RoleRepository.class);
            var assignments = mock(UserRoleAssignmentRepository.class);
            initializer(declared, jdbc, roles, assignments).run();
            verify(roles, never()).findByNameIgnoreCase(anyString());
            verifyNoInteractions(jdbc);
            verifyNoInteractions(assignments);
        }
    }

    /**
     * The negative case has to have a positive twin, or "refuses everything" would pass it.
     */
    @Test
    @DisplayName("kanonik beyan verildiğinde bootstrap çalışır")
    void proceedsOnceTheIdSpaceIsDeclaredCanonical() {
        var jdbc = mock(JdbcTemplate.class);
        var roles = mock(RoleRepository.class);
        var assignments = mock(UserRoleAssignmentRepository.class);
        var adminRole = new Role();
        adminRole.setId(1L);
        adminRole.setName("ADMIN");
        when(roles.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(adminRole));
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 5L, "email", "admin@example.com")));
        when(assignments.findActiveAssignment(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        initializer(DefaultAdminRoleAssignmentInitializer.CANONICAL_ID_SPACE, jdbc, roles, assignments).run();

        verify(assignments).save(any());
    }

    /** The literal is the contract with deployment config; a rename silently disables it. */
    @Test
    @DisplayName("beklenen beyan değeri 'canonical' olarak sabit")
    void theExpectedDeclarationIsStable() {
        org.assertj.core.api.Assertions
                .assertThat(DefaultAdminRoleAssignmentInitializer.CANONICAL_ID_SPACE)
                .isEqualTo("canonical");
    }
}
