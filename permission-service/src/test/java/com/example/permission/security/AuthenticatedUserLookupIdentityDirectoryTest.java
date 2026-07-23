package com.example.permission.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.commonauth.identity.ResolvedUserIdentity;
import com.example.commonauth.identity.UserIdentityDirectoryUnavailableException;
import com.example.permission.service.AuthenticatedUserLookupService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * board #2532 wire step — the adapter's failure semantics ARE the point.
 *
 * <p>The pre-wire code returned {@code null} on any lookup failure, so an outage silently became
 * a deny that then fell back to the token's raw {@code sub} — a Keycloak UUID checked against
 * OpenFGA tuples written for the numeric id. This adapter's contract removes that fallback:
 * "no numeric id" is empty (a real "no such user"); everything else is
 * {@link UserIdentityDirectoryUnavailableException} → resolver
 * {@link com.example.commonauth.identity.AuthenticatedPrincipalResolver.Outcome#DIRECTORY_UNAVAILABLE}
 * → HTTP 503.
 */
class AuthenticatedUserLookupIdentityDirectoryTest {

    private static final String SUB = "2fd0e4f7-c9da-4622-b4b6-b90adab28dd4";
    private static final String EMAIL = "admin@example.com";
    private static final String ISSUER = "https://kc.example.test/realms/main";

    private AuthenticatedUserLookupService lookupService;
    private AuthenticatedUserLookupIdentityDirectory directory;

    private void init() {
        lookupService = mock(AuthenticatedUserLookupService.class);
        directory = new AuthenticatedUserLookupIdentityDirectory(lookupService);
    }

    @Test
    @DisplayName("numeric id resolved → canonical ResolvedUserIdentity (email fallback path)")
    void resolvesNumericIdentity() {
        init();
        when(lookupService.resolve(any(Jwt.class)))
                .thenReturn(new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(
                        1204L, "1204", EMAIL));

        Optional<ResolvedUserIdentity> resolved = directory.resolve(ISSUER, SUB, EMAIL);

        assertTrue(resolved.isPresent());
        ResolvedUserIdentity id = resolved.get();
        assertEquals(1204L, id.userId(),
                "the OpenFGA subject must be the numeric platform id, never the KC UUID");
        assertEquals(EMAIL, id.email());
        assertEquals(SUB, id.subject());
        // The wrapped path does NOT consult kc_subject, so subjectMatched is deliberately false.
        // The follow-up PR that migrates to the canonical /resolve endpoint sets this from the
        // authoritative row and enables back-fill.
        assertFalse(id.subjectMatched());
        assertTrue(id.usable(),
                "wrapped lookup does not surface disabled/deleted state; conservative defaults keep usable=true");
        assertNull(id.companyId(), "companyId is not on the wrapped wire; adapter leaves it null");
    }

    @Test
    @DisplayName("numericUserId null → Optional.empty() (a real 'no such user' answer)")
    void unresolvedNumericIdIsEmpty() {
        init();
        // The lookup service returns null on the numeric id when neither claim nor email finds a
        // row — this is the only signal the adapter treats as "no canonical user".
        when(lookupService.resolve(any(Jwt.class)))
                .thenReturn(new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(
                        null, SUB, EMAIL));

        Optional<ResolvedUserIdentity> resolved = directory.resolve(ISSUER, SUB, EMAIL);

        assertTrue(resolved.isEmpty(),
                "no numeric id is the only legitimate empty answer — the resolver maps it to PROFILE_MISSING");
    }

    @Test
    @DisplayName("null resolution from lookup service is also treated as 'no such user'")
    void nullResolutionIsEmpty() {
        init();
        when(lookupService.resolve(any(Jwt.class))).thenReturn(null);

        Optional<ResolvedUserIdentity> resolved = directory.resolve(ISSUER, SUB, EMAIL);

        assertTrue(resolved.isEmpty());
    }

    @Test
    @DisplayName("lookup throws → UserIdentityDirectoryUnavailableException (NOT empty, NOT a claim fallback)")
    void lookupTransportFailureThrowsUnavailable() {
        init();
        // The wrapped path already collapses SQL / RestClient failures to null, but if any
        // RuntimeException reaches the adapter the fail-closed contract MUST fire: an outage is
        // not "no such user", and it must not degrade into a deny/allow decision.
        when(lookupService.resolve(any(Jwt.class)))
                .thenThrow(new RuntimeException("db pool exhausted"));

        UserIdentityDirectoryUnavailableException ex = assertThrows(
                UserIdentityDirectoryUnavailableException.class,
                () -> directory.resolve(ISSUER, SUB, EMAIL));
        assertNotNull(ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("subject-only token still resolves (email fallback path exercised inside the lookup)")
    void subjectOnlyResolves() {
        init();
        when(lookupService.resolve(any(Jwt.class)))
                .thenReturn(new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(
                        1204L, "1204", null));

        Optional<ResolvedUserIdentity> resolved = directory.resolve(ISSUER, SUB, null);

        assertTrue(resolved.isPresent());
        assertEquals(1204L, resolved.get().userId());
        assertNull(resolved.get().email(),
                "the adapter does not fabricate an email when the wrapped lookup didn't return one");
    }
}
