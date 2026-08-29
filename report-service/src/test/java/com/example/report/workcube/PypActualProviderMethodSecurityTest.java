package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.report.workcube.PypActualProviderDtos.PypActualPage;
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
@ContextConfiguration(classes = PypActualProviderMethodSecurityTest.TestConfig.class)
class PypActualProviderMethodSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        PypActualProviderService pypActualProviderService() {
            return mock(PypActualProviderService.class);
        }

        @Bean
        PypActualProviderController pypActualProviderController(
                PypActualProviderService service) {
            return new PypActualProviderController(service);
        }
    }

    @Autowired PypActualProviderController controller;
    @Autowired PypActualProviderService service;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        org.mockito.Mockito.reset(service);
    }

    @Test
    void deniesCallerWithoutBudgetScopeBeforeProviderRead() {
        authenticate("SCOPE_report:read");

        assertThatThrownBy(() -> controller.find("1", 2026, null, 100))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void budgetReadScopeReachesCompanyAuthorizationService() {
        authenticate("SCOPE_budget:read");
        when(service.findAuthorized(any(), anyLong(), anyInt(), any(), anyInt()))
                .thenReturn(new PypActualPage(List.of(), null, false));

        controller.find("1", 2026, null, 100);

        verify(service).findAuthorized(any(), anyLong(), anyInt(), any(), anyInt());
    }

    @Test
    void budgetWriteScopeReachesCompanyAuthorizationService() {
        authenticate("SCOPE_budget:write");
        when(service.findAuthorized(any(), anyLong(), anyInt(), any(), anyInt()))
                .thenReturn(new PypActualPage(List.of(), null, false));

        controller.find("1", 2026, null, 100);

        verify(service).findAuthorized(any(), anyLong(), anyInt(), any(), anyInt());
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
