package com.example.commonauth.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * board #2532 — identity resolution contract.
 *
 * <p>Regression anchor: endpoint-admin used to resolve the OpenFGA subject as
 * {@code userId claim ?: jwt.getSubject()}, i.e. a Keycloak UUID for normal browser tokens, while
 * every tuple is written for the numeric platform id → authorized admin got 403.
 */
class AuthenticatedPrincipalResolverTest {

    private static final String SUB = "2fd0e4f7-c9da-4622-b4b6-b90adab28dd4";
    private static final String EMAIL = "admin@example.com";

    private UserIdentityDirectory directory;
    private AuthenticatedPrincipalResolver resolver;

    @BeforeEach
    void setUp() {
        directory = mock(UserIdentityDirectory.class);
        resolver = new AuthenticatedPrincipalResolver(directory);
    }

    private static Jwt.Builder base() {
        return Jwt.withTokenValue("t").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .issuer("https://testai.acik.com/realms/platform-test");
    }

    private static Jwt browserToken() {
        // What a real SPA login carries: sub + email, NO userId claim.
        return base().subject(SUB).claim("email", EMAIL).build();
    }

    private static ResolvedUserIdentity identity(long id, boolean enabled, boolean deleted) {
        return new ResolvedUserIdentity(id, SUB, EMAIL, true, enabled, deleted, 1L);
    }

    @Test
    @DisplayName("browser token (sub+email, no userId claim) resolves to the NUMERIC id — not the KC UUID")
    void browserTokenResolvesToNumericId() {
        when(directory.resolve(any(), eq(SUB), eq(EMAIL))).thenReturn(Optional.of(identity(1204L, true, false)));

        var r = resolver.resolve(browserToken());

        assertTrue(r.resolved());
        assertEquals(1204L, r.identity().userId(),
                "the OpenFGA subject must be the numeric id; using jwt.getSubject() was the 403 bug");
    }

    @Test
    @DisplayName("email is lower-cased before lookup (claim casing must not miss the row)")
    void emailNormalised() {
        when(directory.resolve(any(), any(), eq(EMAIL))).thenReturn(Optional.of(identity(1204L, true, false)));

        var r = resolver.resolve(base().subject(SUB).claim("email", "Admin@Example.COM").build());

        assertTrue(r.resolved());
        verify(directory).resolve(any(), eq(SUB), eq(EMAIL));
    }

    @Test
    @DisplayName("userId claim is a HINT: agreeing claim is fine")
    void agreeingClaimAccepted() {
        when(directory.resolve(any(), any(), any())).thenReturn(Optional.of(identity(1204L, true, false)));

        var r = resolver.resolve(base().subject(SUB).claim("email", EMAIL).claim("userId", "1204").build());

        assertTrue(r.resolved());
        assertEquals(1204L, r.identity().userId());
    }

    @Test
    @DisplayName("SECURITY: a userId claim contradicting the canonical row is REJECTED, not honoured")
    void contradictingClaimRejected() {
        when(directory.resolve(any(), any(), any())).thenReturn(Optional.of(identity(1204L, true, false)));

        var r = resolver.resolve(base().subject(SUB).claim("email", EMAIL).claim("userId", "9999").build());

        assertEquals(AuthenticatedPrincipalResolver.Outcome.IDENTITY_MISMATCH, r.outcome(),
                "a stale/forged claim must not select another identity");
        assertNull(r.identity());
    }

    @Test
    @DisplayName("unparsable userId claim is ignored (a malformed hint is not an identity)")
    void unparsableClaimIgnored() {
        when(directory.resolve(any(), any(), any())).thenReturn(Optional.of(identity(1204L, true, false)));

        var r = resolver.resolve(base().subject(SUB).claim("email", EMAIL).claim("userId", "not-a-number").build());

        assertTrue(r.resolved());
        assertEquals(1204L, r.identity().userId());
    }

    @Test
    @DisplayName("no canonical row → PROFILE_MISSING, and NO fallback to the raw sub")
    void noRowMeansProfileMissing() {
        when(directory.resolve(any(), any(), any())).thenReturn(Optional.empty());

        var r = resolver.resolve(browserToken());

        assertEquals(AuthenticatedPrincipalResolver.Outcome.PROFILE_MISSING, r.outcome());
        assertNull(r.identity(), "falling back to jwt.getSubject() here is exactly the #2532 defect");
    }

    @Test
    @DisplayName("directory outage → DIRECTORY_UNAVAILABLE (503), never a deny built on unverified claims")
    void outageIsNotAnAuthorizationAnswer() {
        when(directory.resolve(any(), any(), any()))
                .thenThrow(new UserIdentityDirectoryUnavailableException("user-service timeout"));

        var r = resolver.resolve(base().subject(SUB).claim("email", EMAIL).claim("userId", "1204").build());

        assertEquals(AuthenticatedPrincipalResolver.Outcome.DIRECTORY_UNAVAILABLE, r.outcome());
        assertNull(r.identity(), "an outage must not promote the userId claim to authority");
    }

    @Test
    @DisplayName("disabled account is denied BEFORE any downstream authz call (activation gate)")
    void disabledDenied() {
        when(directory.resolve(any(), any(), any())).thenReturn(Optional.of(identity(1204L, false, false)));

        var r = resolver.resolve(browserToken());

        assertEquals(AuthenticatedPrincipalResolver.Outcome.ACCOUNT_DISABLED, r.outcome());
        assertNull(r.identity());
    }

    @Test
    @DisplayName("soft-deleted account is denied")
    void deletedDenied() {
        when(directory.resolve(any(), any(), any())).thenReturn(Optional.of(identity(1204L, true, true)));

        var r = resolver.resolve(browserToken());

        assertEquals(AuthenticatedPrincipalResolver.Outcome.USER_DELETED, r.outcome());
    }

    @Test
    @DisplayName("token without sub AND without email cannot be resolved — directory is not even called")
    void noSubNoEmail() {
        var r = resolver.resolve(base().subject(null).build());

        assertEquals(AuthenticatedPrincipalResolver.Outcome.UNAUTHENTICATED, r.outcome());
        verify(directory, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("null jwt → UNAUTHENTICATED")
    void nullJwt() {
        assertEquals(AuthenticatedPrincipalResolver.Outcome.UNAUTHENTICATED, resolver.resolve(null).outcome());
        verify(directory, never()).resolve(any(), any(), any());
    }

    @Test
    @DisplayName("directory is consulted exactly once per resolve (no double lookup per request)")
    void singleLookup() {
        when(directory.resolve(any(), any(), any())).thenReturn(Optional.of(identity(1204L, true, false)));

        resolver.resolve(browserToken());

        verify(directory, times(1)).resolve(any(), any(), any());
    }
}
