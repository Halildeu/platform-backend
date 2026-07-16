package com.example.user.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * board #2532 — narrow identity-resolution surface.
 *
 * <p>Exists because the only alternative ({@code /internal/by-email}) returns the password hash and
 * the role catalogue, and because falling back to {@code jwt.getSubject()} (a Keycloak UUID) as the
 * OpenFGA subject is what makes an authorized admin receive 403.
 */
class AuthenticatedPrincipalInternalControllerTest {

    private static final String SUB = "2fd0e4f7-c9da-4622-b4b6-b90adab28dd4";
    private static final String EMAIL = "admin@example.com";

    private UserRepository repo;
    private AuthenticatedPrincipalInternalController controller;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        controller = new AuthenticatedPrincipalInternalController(repo);
        authenticateWith("PERM_users:internal");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateWith(String... authorities) {
        var auths = List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("svc", "n/a", auths));
    }

    private static User user(long id, String sub, String email, boolean enabled, LocalDateTime deletedAt) {
        User u = new User();
        u.setId(id);
        u.setKcSubject(sub);
        u.setEmail(email);
        u.setEnabled(enabled);
        u.setDeletedAt(deletedAt);
        u.setCompanyId(1L);
        u.setPassword("$2a$10$do-not-leak-this");
        return u;
    }

    private AuthenticatedPrincipalInternalController.ResolveRequest req(String sub, String email) {
        return new AuthenticatedPrincipalInternalController.ResolveRequest("iss", sub, email);
    }

    @Test
    @DisplayName("subject binding is the primary path → numeric id, subjectMatched=true")
    void resolvesBySubject() {
        when(repo.findByKcSubject(SUB)).thenReturn(Optional.of(user(1204L, SUB, EMAIL, true, null)));

        var res = controller.resolve(req(SUB, EMAIL));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(1204L, res.getBody().userId(), "callers must get the NUMERIC id, never the KC UUID");
        assertTrue(res.getBody().subjectMatched());
        verify(repo, never()).findByEmailIgnoreCase(any());
    }

    @Test
    @DisplayName("response carries NO password / role / session payload (narrow by construction)")
    void responseIsNarrow() {
        when(repo.findByKcSubject(SUB)).thenReturn(Optional.of(user(1204L, SUB, EMAIL, true, null)));

        var body = controller.resolve(req(SUB, EMAIL)).getBody();

        // The record's component list IS the contract — a future field addition must be deliberate.
        var components = List.of(body.getClass().getRecordComponents()).stream()
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertEquals(List.of("userId", "subjectMatched", "email", "enabled", "deleted", "companyId"),
                components, "identity resolution must not widen into a credential surface");
    }

    @Test
    @DisplayName("legacy row without kc_subject resolves by email AND back-fills the binding")
    void emailFallbackBackfills() {
        when(repo.findByKcSubject(SUB)).thenReturn(Optional.empty());
        User legacy = user(1204L, null, EMAIL, true, null);
        when(repo.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(legacy));

        var res = controller.resolve(req(SUB, EMAIL));

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(1204L, res.getBody().userId());
        assertTrue(res.getBody().subjectMatched(), "binding back-filled → subsequent resolves take the primary path");
        assertEquals(SUB, legacy.getKcSubject());
        verify(repo).save(legacy);
    }

    @Test
    @DisplayName("SECURITY: a row bound to ANOTHER subject is not handed over even if the email matches")
    void doesNotHandOverForeignBinding() {
        when(repo.findByKcSubject(SUB)).thenReturn(Optional.empty());
        when(repo.findByEmailIgnoreCase(EMAIL))
                .thenReturn(Optional.of(user(1204L, "someone-elses-subject", EMAIL, true, null)));

        var res = controller.resolve(req(SUB, EMAIL));

        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode(),
                "emails are reassignable, subject bindings are not — this would be identity takeover");
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("email lookup is case-insensitive and normalised")
    void emailNormalised() {
        when(repo.findByKcSubject(any())).thenReturn(Optional.empty());
        when(repo.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user(1204L, SUB, EMAIL, true, null)));

        controller.resolve(req(null, "Admin@Example.COM"));

        verify(repo).findByEmailIgnoreCase(eq(EMAIL));
    }

    @Test
    @DisplayName("activation and deletion state are reported, not hidden")
    void reportsState() {
        when(repo.findByKcSubject(SUB))
                .thenReturn(Optional.of(user(1204L, SUB, EMAIL, false, LocalDateTime.now())));

        var body = controller.resolve(req(SUB, EMAIL)).getBody();

        assertFalse(body.enabled(), "the caller enforces the activation gate — it needs the truth");
        assertTrue(body.deleted());
    }

    @Test
    @DisplayName("no such user → 404 (caller must deny, never fall back to the raw sub)")
    void notFound() {
        when(repo.findByKcSubject(any())).thenReturn(Optional.empty());
        when(repo.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.resolve(req(SUB, EMAIL)).getStatusCode());
    }

    @Test
    @DisplayName("neither subject nor email → 400 (caller bug, not 'no such user')")
    void badRequestWithoutBindingKeys() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.resolve(req(null, "  ")).getStatusCode());
        verify(repo, never()).findByKcSubject(any());
        verify(repo, never()).findByEmailIgnoreCase(any());
    }

    @Test
    @DisplayName("service authority is required — no authority, no identity resolution")
    void requiresServiceAuthority() {
        authenticateWith("PERM_something:else");
        assertThrows(AccessDeniedException.class, () -> controller.resolve(req(SUB, EMAIL)));

        SecurityContextHolder.clearContext();
        assertThrows(AccessDeniedException.class, () -> controller.resolve(req(SUB, EMAIL)));
        verify(repo, never()).findByKcSubject(any());
    }
}
