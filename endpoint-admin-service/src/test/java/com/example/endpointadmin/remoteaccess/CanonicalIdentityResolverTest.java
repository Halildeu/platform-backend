package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.CanonicalIdentityResolverFactory.ResolverType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — the canonical-identity resolver + the dual-control invariant enforced on
 * the canonical subject. The point of the slice: one human who requests under {@code u1} and approves under an
 * alias / proxy / service-account that resolves to the SAME canonical subject is DENIED (no self-approval via a
 * second id); an unresolvable principal is fail-closed.
 */
class CanonicalIdentityResolverTest {

    // u1 owns the alias 'u1-alias' + the service-account 'svc-deploy-u1' + the proxy 'proxy->u1'; u2 is distinct
    private static final Map<String, String> MAPPING = Map.of(
            "u1", "u1",
            "u1-alias", "u1",
            "svc-deploy-u1", "u1",
            "proxy->u1", "u1",
            "u2", "u2",
            "u2-alias", "u2");

    private static CanonicalIdentityResolver resolver() {
        return new InMemoryCanonicalIdentityResolver(MAPPING);
    }

    // ---- the reference resolver ----

    @Test
    void aMappedPrincipalResolvesToItsCanonicalSubject() {
        assertEquals("u1", resolver().canonicalSubject("u1").orElseThrow());
        assertEquals("u1", resolver().canonicalSubject("u1-alias").orElseThrow());
        assertEquals("u1", resolver().canonicalSubject("svc-deploy-u1").orElseThrow());
        assertEquals("u1", resolver().canonicalSubject(" u1-alias ").orElseThrow(), "the presented id is trimmed");
        assertEquals("u2", resolver().canonicalSubject("u2-alias").orElseThrow());
    }

    @Test
    void anUnmappedOrBlankPrincipalIsUnresolvableFailClosed() {
        assertTrue(resolver().canonicalSubject("unknown-principal").isEmpty(), "no pass-through for an unmapped id");
        assertTrue(resolver().canonicalSubject("  ").isEmpty());
        assertTrue(resolver().canonicalSubject(null).isEmpty());
    }

    @Test
    void aBlankMappingEntryIsRejectedAtConstruction() {
        // a blank principal or canonical subject would be a fail-open hole
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryCanonicalIdentityResolver(java.util.Collections.singletonMap("", "u1")));
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryCanonicalIdentityResolver(java.util.Collections.singletonMap("u1", " ")));
    }

    // ---- the factory blocking-matrix ----

    @Test
    void inMemoryIsTheReferenceOutsideProdButForbiddenInProd() {
        assertInstanceOf(InMemoryCanonicalIdentityResolver.class,
                CanonicalIdentityResolverFactory.create(ResolverType.IN_MEMORY, MAPPING, false));
        assertThrows(IllegalStateException.class,
                () -> CanonicalIdentityResolverFactory.create(ResolverType.IN_MEMORY, MAPPING, true));
    }

    @Test
    void theIdpBackedResolverFailsFastUntilImplemented() {
        assertThrows(IllegalStateException.class,
                () -> CanonicalIdentityResolverFactory.create(ResolverType.IDP_BACKED, MAPPING, false));
        assertThrows(IllegalStateException.class,
                () -> CanonicalIdentityResolverFactory.create(ResolverType.IDP_BACKED, MAPPING, true));
    }

    @Test
    void aNullTypeDefaultsToInMemoryOutsideProdButIsForbiddenInProd() {
        assertInstanceOf(InMemoryCanonicalIdentityResolver.class,
                CanonicalIdentityResolverFactory.create(null, MAPPING, false));
        assertThrows(IllegalStateException.class,
                () -> CanonicalIdentityResolverFactory.create(null, MAPPING, true));
    }

    // ---- dual-control on the CANONICAL subject (the slice's reason for being) ----

    @Test
    void selfApprovalViaAnAliasIsDenied() {
        CanonicalIdentityResolver r = resolver();
        // u1 requests, then "approves" under an alias / SA / proxy that canonicalizes to u1 → DENIED
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequesterCanonical(r, "u1", "u1-alias"));
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequesterCanonical(r, "u1", "svc-deploy-u1"));
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequesterCanonical(r, "u1-alias", "proxy->u1"));
        // and the raw-equal case still denied
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequesterCanonical(r, "u1", "u1"));
    }

    @Test
    void twoGenuinelyDistinctCanonicalSubjectsAreAllowed() {
        CanonicalIdentityResolver r = resolver();
        assertTrue(RemoteSessionAuthz.approverDistinctFromRequesterCanonical(r, "u1", "u2"));
        assertTrue(RemoteSessionAuthz.approverDistinctFromRequesterCanonical(r, "u1-alias", "u2-alias"));
    }

    @Test
    void anUnresolvablePrincipalOrNullResolverIsFailClosed() {
        CanonicalIdentityResolver r = resolver();
        // an unmapped requester or approver cannot be proven distinct → DENIED (never fall back to raw ids)
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequesterCanonical(r, "u1", "unmapped-approver"));
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequesterCanonical(r, "unmapped-requester", "u2"));
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequesterCanonical(r, "  ", "u2"));
        assertFalse(RemoteSessionAuthz.approverDistinctFromRequesterCanonical(null, "u1", "u2"));
    }
}
