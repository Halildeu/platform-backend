package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.report.workcube.ProjectActualProviderDtos.ProjectActualPage;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ProjectActualProviderMethodSecurityTest.TestConfig.class)
class ProjectActualProviderMethodSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        ProjectActualProviderService projectActualProviderService() {
            return mock(ProjectActualProviderService.class);
        }

        @Bean
        ProjectActualProviderController projectActualProviderController(
                ProjectActualProviderService service) {
            return new ProjectActualProviderController(service);
        }
    }

    @Autowired ProjectActualProviderController controller;
    @Autowired ProjectActualProviderService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void deniesCallerWithoutBudgetScopeBeforeProviderRead() {
        authenticate("SCOPE_report:read");

        assertThatThrownBy(() -> find())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void budgetReadScopeReachesProjectAuthorizationService() {
        authenticate("SCOPE_budget:read");
        when(service.findAuthorized(any(), anyLong(), anyLong(), any(), any(), any(), anyInt()))
                .thenReturn(new ProjectActualPage(List.of(), null, false));

        find();

        verify(service).findAuthorized(any(), anyLong(), anyLong(), any(), any(), any(), anyInt());
    }

    private void find() {
        controller.find(
                "35",
                44200L,
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30),
                null,
                100);
    }

    private void authenticate(String authority) {
        Jwt jwt = Jwt.withTokenValue("synthetic")
                .header("alg", "none")
                .subject("1204")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(
                        jwt,
                        List.of(new SimpleGrantedAuthority(authority))));
    }
}
