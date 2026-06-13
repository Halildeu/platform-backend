package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.AuthzGrantResolverFactory.GrantSource;
import com.example.endpointadmin.remoteaccess.InMemoryAuthzGrantResolver.Grant;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — the in-memory reference {@link AuthzGrantResolver} is a fail-closed static
 * grant-set (absent/blank → no grant; trimmed lookup; blank + normalized-duplicate grants rejected at
 * construction), and the {@link AuthzGrantResolverFactory} blocking matrix forbids the placeholder in a
 * production-like profile and refuses the not-yet-implemented OpenFGA-backed resolver.
 */
class AuthzGrantResolverTest {

    private static final String RES = "res1";

    private static InMemoryAuthzGrantResolver resolver() {
        return new InMemoryAuthzGrantResolver(
                Set.of(new Grant("u1", RES)),                       // can_request
                Set.of(new Grant("u2", RES), new Grant("u1", RES))); // can_approve
    }

    @Test
    void aConfiguredGrantIsPresentAndAnAbsentOneIsNot() {
        AuthzGrantResolver r = resolver();
        assertTrue(r.hasCanRequest("u1", RES));
        assertFalse(r.hasCanRequest("u2", RES));   // u2 may approve but not request
        assertTrue(r.hasCanApprove("u2", RES));
        assertFalse(r.hasCanApprove("u3", RES));   // u3 holds nothing
        assertFalse(r.hasCanRequest("u1", "other-res")); // grant is resource-scoped
    }

    @Test
    void aBlankOrNullPrincipalOrResourceHasNoGrant() {
        AuthzGrantResolver r = resolver();
        assertFalse(r.hasCanRequest(null, RES));
        assertFalse(r.hasCanRequest("  ", RES));
        assertFalse(r.hasCanRequest("u1", null));
        assertFalse(r.hasCanRequest("u1", "  "));
        assertFalse(r.hasCanApprove(null, null));
    }

    @Test
    void theLookupTrimsSoWhitespaceMatchesTheConfiguredGrant() {
        AuthzGrantResolver r = resolver();
        assertTrue(r.hasCanRequest("  u1  ", "  " + RES + "  "));
    }

    @Test
    void aBlankGrantIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryAuthzGrantResolver(Set.of(new Grant("  ", RES)), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryAuthzGrantResolver(Set.of(new Grant("u1", "  ")), Set.of()));
    }

    @Test
    void aNormalizedDuplicateGrantIsRejected() {
        // "u1" and " u1 " on the same resource both normalize to (u1,res1) → ambiguous config → reject
        assertThrows(IllegalArgumentException.class, () -> new InMemoryAuthzGrantResolver(
                Set.of(new Grant("u1", RES), new Grant(" u1 ", RES)), Set.of()));
    }

    @Test
    void aNullGrantSetIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryAuthzGrantResolver(null, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryAuthzGrantResolver(Set.of(), null));
    }

    @Test
    void theFactoryBuildsTheInMemoryResolverOutsideProduction() {
        AuthzGrantResolver r = AuthzGrantResolverFactory.create(
                GrantSource.IN_MEMORY, Set.of(new Grant("u1", RES)), Set.of(new Grant("u2", RES)), false);
        assertNotNull(r);
        assertTrue(r.hasCanRequest("u1", RES));
    }

    @Test
    void theFactoryForbidsTheInMemoryPlaceholderInProduction() {
        assertThrows(IllegalStateException.class, () -> AuthzGrantResolverFactory.create(
                GrantSource.IN_MEMORY, Set.of(), Set.of(), true));
    }

    @Test
    void theFactoryRefusesTheNotYetImplementedOpenFgaResolver() {
        assertThrows(IllegalStateException.class, () -> AuthzGrantResolverFactory.create(
                GrantSource.OPENFGA_BACKED, Set.of(), Set.of(), false));
    }

    @Test
    void aNullTypeDefaultsToInMemoryAndIsStillProductionForbidden() {
        // null → IN_MEMORY placeholder default; still forbidden in a production-like profile
        assertThrows(IllegalStateException.class,
                () -> AuthzGrantResolverFactory.create(null, Set.of(), Set.of(), true));
        assertNotNull(AuthzGrantResolverFactory.create(null, Set.of(), Set.of(), false));
    }
}
