package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D10 approval-chain (Codex 019ebe06) — the tenant-scoped resolver grants a principal {@code can_request}
 * / {@code can_approve} for ANY (dynamic) session in a granted tenant, parsing the tenant out of
 * {@code remote_session:<tenant>:<sessionId>}. Fail-closed on a foreign tenant, a missing relation, a blank
 * principal, or a malformed resource.
 */
class TenantScopedAuthzGrantResolverTest {

    private static final String T1 = "11111111-1111-1111-1111-111111111111";
    private static final String T2 = "22222222-2222-2222-2222-222222222222";
    private static final String OP = "operator@x";
    private static final String AP = "approver@y";

    private static String res(String tenant, String sessionId) {
        return "remote_session:" + tenant + ":" + sessionId;
    }

    private static AuthzGrantResolver resolver() {
        // OP may request in T1; AP may approve in T1 and T2
        return new TenantScopedAuthzGrantResolver(Map.of(OP, Set.of(T1)), Map.of(AP, Set.of(T1, T2)));
    }

    @Test
    void grantsAnyDynamicSessionInAGrantedTenant() {
        AuthzGrantResolver r = resolver();
        // the KEY property: the grant holds for ANY sessionId in the tenant (the resource's sessionId is dynamic)
        assertTrue(r.hasCanRequest(OP, res(T1, "sess-abc")));
        assertTrue(r.hasCanRequest(OP, res(T1, "a-totally-different-session-id")));
        assertTrue(r.hasCanApprove(AP, res(T1, "s1")));
        assertTrue(r.hasCanApprove(AP, res(T2, "s2")));
    }

    @Test
    void deniesAForeignTenantOrAMissingRelation() {
        AuthzGrantResolver r = resolver();
        assertFalse(r.hasCanRequest(OP, res(T2, "s1")));   // OP not granted request in T2
        assertFalse(r.hasCanApprove(OP, res(T1, "s1")));   // OP holds no approve grant at all
        assertFalse(r.hasCanRequest(AP, res(T1, "s1")));   // AP holds no request grant
    }

    @Test
    void failsClosedOnBlankOrMalformedResourceOrPrincipal() {
        AuthzGrantResolver r = resolver();
        assertFalse(r.hasCanRequest(OP, null));
        assertFalse(r.hasCanRequest(OP, "  "));
        assertFalse(r.hasCanRequest(OP, "not-a-remote-session:" + T1 + ":s1")); // wrong prefix
        assertFalse(r.hasCanRequest(OP, "remote_session:" + T1));               // no sessionId segment
        assertFalse(r.hasCanRequest(OP, "remote_session::s1"));                  // blank tenant
        assertFalse(r.hasCanRequest(null, res(T1, "s1")));
        assertFalse(r.hasCanRequest("  ", res(T1, "s1")));
    }

    @Test
    void aSessionIdContainingColonsStillResolvesTheTenant() {
        // the wire-id charset includes ':', so a sessionId may contain colons; the 3-limit split keeps the tenant
        assertTrue(resolver().hasCanRequest(OP, res(T1, "sess:with:colons")));
    }

    @Test
    void ctorRejectsBlankOrEmptyConfig() {
        assertThrows(IllegalArgumentException.class, () -> new TenantScopedAuthzGrantResolver(null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TenantScopedAuthzGrantResolver(Map.of(OP, Set.of()), Map.of()));       // no tenants
        assertThrows(IllegalArgumentException.class,
                () -> new TenantScopedAuthzGrantResolver(Map.of("  ", Set.of(T1)), Map.of()));    // blank principal
        assertThrows(IllegalArgumentException.class,
                () -> new TenantScopedAuthzGrantResolver(Map.of(OP, Set.of("  ")), Map.of()));    // blank tenant
    }

    @Test
    void ctorRejectsANonCanonicalUuidTenant() {
        // the session/JWT tenant is always a canonical UUID — a non-canonical grant tenant would silently never
        // match, so it is a fail-fast pilot-config error (Codex follow-up)
        assertThrows(IllegalArgumentException.class,
                () -> new TenantScopedAuthzGrantResolver(Map.of(OP, Set.of("tenant-1")), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new TenantScopedAuthzGrantResolver(
                Map.of(OP, Set.of("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")), Map.of())); // uppercase = non-canonical
    }
}
