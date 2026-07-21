package com.example.user.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Board #2582 — an identity that does not state its own organisation must be COUNTED, not silently
 * defaulted.
 *
 * <p><b>The measured problem.</b> On testai 2026-07-17, 10 of 12 rows in {@code users_db.users}
 * carried a null {@code company_id} — including the admin account. Downstream the plane substitutes
 * {@code companyId=1} and derives the org {@code UUID.nameUUIDFromBytes("company:1")}, so the admin
 * resolves into an organisation it never declared. Nothing logged it and nothing counted it, which
 * is how the gap survived long enough to become the root of the #2559 empty-fleet symptom.
 *
 * <p><b>What this test pins, and what it deliberately does not.</b> It pins the SIGNAL: the counter
 * moves for an org-less principal and stays still for a normal one. It also pins that the
 * authorization outcome is UNCHANGED — same 200, same body, {@code companyId} still null rather than
 * coerced to 1. Failing closed here would lock out the admin plus nine live users, and choosing an
 * org for them is an owner decision with irreversible consequences (#2582 madde 3). Those belong to
 * later slices; this one only removes the silence.
 */
class AuthenticatedPrincipalOrglessSignalTest {

    private static final String SUB = "2fd0e4f7-c9da-4622-b4b6-b90adab28dd4";
    private static final String EMAIL = "admin@example.com";
    private static final String METRIC = "identity_principal_without_company_total";

    private UserRepository repo;
    private MeterRegistry meterRegistry;
    private AuthenticatedPrincipalInternalController controller;

    @BeforeEach
    void setUp() {
        repo = mock(UserRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        controller = new AuthenticatedPrincipalInternalController(repo, meterRegistry);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("svc", "n/a",
                        List.of(new SimpleGrantedAuthority("PERM_users:internal"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static User user(Long companyId) {
        User u = new User();
        u.setId(1L);
        u.setKcSubject(SUB);
        u.setEmail(EMAIL);
        u.setEnabled(true);
        u.setCompanyId(companyId);
        u.setPassword("$2a$10$do-not-leak-this");
        return u;
    }

    private double counterValue() {
        var counter = meterRegistry.find(METRIC).counter();
        return counter == null ? 0d : counter.count();
    }

    private ResponseEntity<AuthenticatedPrincipalInternalController.ResolveResponse> resolve() {
        return controller.resolve(
                new AuthenticatedPrincipalInternalController.ResolveRequest("iss", SUB, EMAIL));
    }

    @Test
    @DisplayName("#2582: org-less principal increments the counter")
    void orglessPrincipalIsCounted() {
        when(repo.findByKcSubject(anyString())).thenReturn(Optional.of(user(null)));

        resolve();

        assertEquals(1.0, counterValue(),
                "a principal with null company_id must be counted — this is the population the "
                        + "#2582 migration has to drain");
    }

    @Test
    @DisplayName("#2582: a principal WITH a company does not move the counter")
    void principalWithCompanyIsNotCounted() {
        when(repo.findByKcSubject(anyString())).thenReturn(Optional.of(user(35L)));

        resolve();

        assertEquals(0.0, counterValue(),
                "counting a healthy identity would make the metric useless as a migration signal");
    }

    @Test
    @DisplayName("#2582: the authorization outcome is UNCHANGED — observe, do not coerce")
    void behaviourIsUnchangedForOrglessPrincipal() {
        when(repo.findByKcSubject(anyString())).thenReturn(Optional.of(user(null)));

        var response = resolve();

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "failing closed here would lock out the admin and nine live users; that is a "
                        + "separate, owner-gated decision (#2582 madde 2)");
        assertNull(response.getBody().companyId(),
                "companyId must be reported as null, NOT coerced to 1 — silently inventing an org "
                        + "for the caller is the defect, and hiding it behind a default here would "
                        + "just move the silence one layer up");
        assertEquals(1L, response.getBody().userId());
    }

    @Test
    @DisplayName("#2582: each resolve of an org-less principal counts again (usage, not row count)")
    void counterTracksUsageNotRows() {
        when(repo.findByKcSubject(anyString())).thenReturn(Optional.of(user(null)));

        resolve();
        resolve();
        resolve();

        assertEquals(3.0, counterValue(),
                "the metric measures org-less identities actually being USED — a row nobody "
                        + "authenticates as is not the urgent case");
    }

    @Test
    @DisplayName("#2582: no MeterRegistry (test/standalone wiring) must not break resolution")
    void worksWithoutMeterRegistry() {
        var noMetrics = new AuthenticatedPrincipalInternalController(repo);
        when(repo.findByKcSubject(anyString())).thenReturn(Optional.of(user(null)));

        var response = noMetrics.resolve(
                new AuthenticatedPrincipalInternalController.ResolveRequest("iss", SUB, EMAIL));

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "the observability addition must never become a hard dependency of identity "
                        + "resolution");
    }
}
