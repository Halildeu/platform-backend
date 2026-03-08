package com.example.permission.config;

import com.example.permission.model.Role;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAdminRoleAssignmentInitializerTest {

    @Test
    void seedsMissingAdminAssignmentsForConfiguredUsers() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        UserRoleAssignmentRepository assignmentRepository = mock(UserRoleAssignmentRepository.class);

        Role adminRole = new Role();
        adminRole.setId(99L);
        adminRole.setName("ADMIN");

        when(roleRepository.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(adminRole));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 7L, "email", "admin@example.com")));
        when(assignmentRepository.findActiveAssignment(7L, null, 99L, null, null)).thenReturn(Optional.empty());

        DefaultAdminRoleAssignmentInitializer initializer = new DefaultAdminRoleAssignmentInitializer(
                jdbcTemplate,
                roleRepository,
                assignmentRepository,
                true,
                "admin@example.com",
                1,
                0,
                "users"
        );

        initializer.run();

        verify(assignmentRepository).save(any(UserRoleAssignment.class));
    }

    @Test
    void skipsWhenAssignmentAlreadyExists() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        UserRoleAssignmentRepository assignmentRepository = mock(UserRoleAssignmentRepository.class);

        Role adminRole = new Role();
        adminRole.setId(99L);
        adminRole.setName("ADMIN");

        UserRoleAssignment existingAssignment = new UserRoleAssignment();
        existingAssignment.setId(1L);

        when(roleRepository.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(adminRole));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 7L, "email", "admin@example.com")));
        when(assignmentRepository.findActiveAssignment(7L, null, 99L, null, null))
                .thenReturn(Optional.of(existingAssignment));

        DefaultAdminRoleAssignmentInitializer initializer = new DefaultAdminRoleAssignmentInitializer(
                jdbcTemplate,
                roleRepository,
                assignmentRepository,
                true,
                "admin@example.com",
                1,
                0,
                "users"
        );

        initializer.run();

        verify(roleRepository).findByNameIgnoreCase("ADMIN");
        assertThat(existingAssignment.getId()).isEqualTo(1L);
    }

    @Test
    void usesConfiguredQualifiedUserTableForLookup() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RoleRepository roleRepository = mock(RoleRepository.class);
        UserRoleAssignmentRepository assignmentRepository = mock(UserRoleAssignmentRepository.class);

        Role adminRole = new Role();
        adminRole.setId(99L);
        adminRole.setName("ADMIN");

        when(roleRepository.findByNameIgnoreCase("ADMIN")).thenReturn(Optional.of(adminRole));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        DefaultAdminRoleAssignmentInitializer initializer = new DefaultAdminRoleAssignmentInitializer(
                jdbcTemplate,
                roleRepository,
                assignmentRepository,
                true,
                "admin@example.com",
                1,
                0,
                "user_service.users"
        );

        initializer.run();

        verify(jdbcTemplate).queryForList(contains("from user_service.users"), any(Object[].class));
    }
}
