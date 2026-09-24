package com.example.user.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.user.keycloak.KeycloakAdminClient;
import com.example.user.keycloak.MicrosoftOrganizerProperties;
import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.security.ServiceAuthenticationToken;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class MicrosoftOrganizerInternalControllerTest {
    static final String SUBJECT = "12345678-1111-2222-3333-123456789012";
    static final String TENANT = "12345678-1111-2222-3333-123456789013";
    static final String OBJECT = "12345678-1111-2222-3333-123456789014";
    static final String ISSUER = "https://identity.example/realms/platform-test";
    UserRepository users;
    KeycloakAdminClient keycloak;
    MicrosoftOrganizerProperties props;
    MicrosoftOrganizerInternalController controller;
    User user;

    @BeforeEach void setup() {
        users = mock(UserRepository.class);
        keycloak = mock(KeycloakAdminClient.class);
        when(keycloak.realm()).thenReturn("platform-test");
        when(keycloak.isEnabled()).thenReturn(true);
        when(keycloak.fetchMicrosoftIdentity(SUBJECT, "microsoft")).thenReturn(Optional.of(
                new KeycloakAdminClient.MicrosoftIdentity(UUID.fromString(TENANT), UUID.fromString(OBJECT))));
        props = new MicrosoftOrganizerProperties();
        props.setEnabled(true); props.setIssuer(ISSUER); props.setTenantId(TENANT);
        controller = new MicrosoftOrganizerInternalController(users, keycloak, props);
        user = new User(); user.setId(42L); user.setCompanyId(12L);
        user.setKcSubject(SUBJECT); user.setEnabled(true);
        when(users.findByKcSubject(SUBJECT)).thenReturn(Optional.of(user));
        grant("PERM_users:internal");
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void grant(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new ServiceAuthenticationToken(
                "meeting-service", "test", List.of(new SimpleGrantedAuthority(authority))));
    }
    MicrosoftOrganizerInternalController.ResolveRequest request() {
        return new MicrosoftOrganizerInternalController.ResolveRequest(ISSUER, SUBJECT);
    }
    int status() { return controller.resolve(request()).getStatusCode().value(); }

    @Test void exactActiveSubjectReturnsOnlyBoundIdentityAndCompany() {
        var response = controller.resolve(request());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(new MicrosoftOrganizerInternalController.ResolveResponse(
                42L, 12L, SUBJECT, UUID.fromString(TENANT), UUID.fromString(OBJECT)));
        verify(users, never()).findByEmailIgnoreCase(any());
        verify(users, never()).save(any());
    }

    @Test void cannotUseAUserTokenWithTheSameAuthorityString() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user", "",
                List.of(new SimpleGrantedAuthority("PERM_users:internal"))));
        assertThatThrownBy(() -> status()).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(users, keycloak);
    }
    @Test void deniesUnrelatedOrUnauthenticatedServiceAndAnonymous() {
        grant("PERM_users:display-names:read");
        assertThatThrownBy(() -> status()).isInstanceOf(ResponseStatusException.class);
        grant("PERM_users:internal");
        SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);
        assertThatThrownBy(() -> status()).isInstanceOf(ResponseStatusException.class);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> status()).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(users);
    }

    @Test void rejectsForeignIssuerBeforeSubjectLookup() {
        assertThat(controller.resolve(new MicrosoftOrganizerInternalController.ResolveRequest(
                "https://other.example/realms/platform-test", SUBJECT)).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(users);
        verify(keycloak, never()).fetchMicrosoftIdentity(any(), any());
    }
    @Test void rejectsForeignMicrosoftTenant() {
        when(keycloak.fetchMicrosoftIdentity(SUBJECT, "microsoft")).thenReturn(Optional.of(
                new KeycloakAdminClient.MicrosoftIdentity(UUID.randomUUID(), UUID.fromString(OBJECT))));
        assertThat(status()).isEqualTo(403);
    }
    @Test void rejectsUnlinkedUnknownAndReboundSubjects() {
        when(keycloak.fetchMicrosoftIdentity(SUBJECT, "microsoft")).thenReturn(Optional.empty());
        assertThat(status()).isEqualTo(403);
        when(users.findByKcSubject(SUBJECT)).thenReturn(Optional.empty());
        assertThat(status()).isEqualTo(403);
        when(users.findByKcSubject(SUBJECT)).thenReturn(Optional.of(user));
        user.setKcSubject(OBJECT);
        assertThat(status()).isEqualTo(403);
    }
    @Test void deniesDisabledDeletedOrCompanylessAccountsWithoutKeycloakReads() {
        user.setEnabled(false); assertThat(status()).isEqualTo(403);
        user.setEnabled(true); user.setDeletedAt(LocalDateTime.now()); assertThat(status()).isEqualTo(403);
        user.setDeletedAt(null); user.setCompanyId(null); assertThat(status()).isEqualTo(403);
        user.setCompanyId(0L); assertThat(status()).isEqualTo(403);
        verify(keycloak, never()).fetchMicrosoftIdentity(any(), any());
    }
    @Test void unavailableDoesNotLeakUpstreamBody() {
        when(keycloak.fetchMicrosoftIdentity(SUBJECT, "microsoft")).thenThrow(new IllegalStateException("secret-body"));
        var response = controller.resolve(request());
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().toString()).doesNotContain("secret-body");
    }
    @Test void disabledByDefaultAndWithoutConfiguredTenant() {
        props.setEnabled(false); assertThat(status()).isEqualTo(503);
        props.setEnabled(true); props.setTenantId(""); assertThat(status()).isEqualTo(503);
        verifyNoInteractions(users);
        assertThat(new MicrosoftOrganizerProperties().isConfiguredFor("platform-test")).isFalse();
    }
    @ParameterizedTest @ValueSource(strings = {"", "../user", "1-1-1-1-1", "00000000-0000-0000-0000-000000000000"})
    void rejectsMalformedSubject(String subject) {
        assertThat(controller.resolve(new MicrosoftOrganizerInternalController.ResolveRequest(ISSUER, subject))
                .getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(users);
    }
    @ParameterizedTest @ValueSource(strings = {"http://identity.example/realms/platform-test",
            "https://identity.example/realms/other", "https://identity.example/realms/platform-test?x=1",
            "https://user@identity.example/realms/platform-test", ""})
    void rejectsWrongRealmOrUnsafeConfiguredIssuer(String issuer) {
        props.setIssuer(issuer); assertThat(status()).isEqualTo(503);
        verifyNoInteractions(users);
    }
}
