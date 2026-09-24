package com.example.user.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.user.keycloak.KeycloakAdminClient;
import com.example.user.keycloak.MicrosoftOrganizerProperties;
import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.security.SecurityConfig;
import com.example.user.security.ServiceAuthenticationToken;
import com.example.user.security.ServiceTokenAuthenticationFilter;
import com.example.user.serviceauth.ServiceTokenVerifier;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Actual non-local security chains and controller; only service verification/directory are stubbed. */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = MicrosoftOrganizerHttpSecurityTest.Config.class)
class MicrosoftOrganizerHttpSecurityTest {
    static final String SUBJECT = "12345678-1111-2222-3333-123456789012";
    static final String TENANT = "12345678-1111-2222-3333-123456789013";
    static final String OBJECT = "12345678-1111-2222-3333-123456789014";
    static final String ISSUER = "https://identity.example/realms/platform-test";
    static final String PATH = "/api/users/internal/microsoft-organizer/resolve";
    static final String BODY = "{\"issuer\":\"" + ISSUER + "\",\"subject\":\"" + SUBJECT + "\"}";
    @Autowired WebApplicationContext context;
    @Autowired ServiceTokenVerifier verifier;
    @Autowired UserRepository users;
    @Autowired KeycloakAdminClient keycloak;
    MockMvc http;

    @Configuration @EnableWebMvc
    @Import({SecurityConfig.class, MicrosoftOrganizerInternalController.class})
    static class Config {
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean KeycloakAdminClient keycloak() { return mock(KeycloakAdminClient.class); }
        @Bean ServiceTokenVerifier verifier() { return mock(ServiceTokenVerifier.class); }
        @Bean ServiceTokenAuthenticationFilter filter(ServiceTokenVerifier verifier) {
            return new ServiceTokenAuthenticationFilter(verifier);
        }
        @Bean MicrosoftOrganizerProperties properties() {
            var props = new MicrosoftOrganizerProperties();
            props.setEnabled(true); props.setIssuer(ISSUER); props.setTenantId(TENANT);
            return props;
        }
    }

    @BeforeEach void setup() {
        reset(verifier, users, keycloak);
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(keycloak.realm()).thenReturn("platform-test"); when(keycloak.isEnabled()).thenReturn(true);
        when(keycloak.fetchMicrosoftIdentity(SUBJECT, "microsoft")).thenReturn(Optional.of(
                new KeycloakAdminClient.MicrosoftIdentity(UUID.fromString(TENANT), UUID.fromString(OBJECT))));
        var user = new User(); user.setId(42L); user.setCompanyId(12L); user.setKcSubject(SUBJECT); user.setEnabled(true);
        when(users.findByKcSubject(SUBJECT)).thenReturn(Optional.of(user));
        when(verifier.verify("valid-service")).thenReturn(new ServiceAuthenticationToken(
                "meeting-service", "test", List.of(new SimpleGrantedAuthority("PERM_users:internal"))));
    }
    @Test void validServiceRequestUsesExactDirectoryIdentity() throws Exception {
        http.perform(post(PATH).header("Authorization", "Bearer valid-service").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.organizerId").value(OBJECT)).andExpect(jsonPath("$.companyId").value(12))
                .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist());
    }
    @Test void anonymousAndInvalidServiceCannotReachDirectory() throws Exception {
        http.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        when(verifier.verify("invalid")).thenThrow(new IllegalArgumentException("invalid service token"));
        http.perform(post(PATH).header("Authorization", "Bearer invalid").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users, keycloak);
    }
    @Test void sameAuthorityOnUserAuthenticationIsStillRejected() throws Exception {
        var user = new UsernamePasswordAuthenticationToken("user", "", List.of(new SimpleGrantedAuthority("PERM_users:internal")));
        http.perform(post(PATH).with(authentication(user)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users, keycloak);
    }
    @Test void wrongServiceScopeCannotReachDirectory() throws Exception {
        when(verifier.verify("valid-service")).thenReturn(new ServiceAuthenticationToken(
                "other", "test", List.of(new SimpleGrantedAuthority("PERM_users:display-names:read"))));
        http.perform(post(PATH).header("Authorization", "Bearer valid-service").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users, keycloak);
    }
}
