package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import static com.example.endpointadmin.remoteaccess.CertBindingGuard.Decision;
import static com.example.endpointadmin.remoteaccess.CertBindingGuard.Policy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B1.1c — the full cert-binding truth table (Codex 019eb54b check-list #1/#3). Pure unit.
 */
class CertBindingGuardTest {

    private static final String TP_A = "ab".repeat(32);
    private static final String TP_B = "cd".repeat(32);

    @Test
    void boundTokenWithExactMatchSatisfies() {
        Decision d = CertBindingGuard.decide(TP_A, TP_A, Policy.REQUIRE_BOUND);
        assertEquals(Decision.BOUND_MATCH, d);
        assertTrue(d.satisfied());
    }

    @Test
    void boundTokenMatchIsHexCaseInsensitive() {
        assertEquals(Decision.BOUND_MATCH,
                CertBindingGuard.decide(TP_A, TP_A.toUpperCase(), Policy.REQUIRE_BOUND));
        assertEquals(Decision.BOUND_MATCH,
                CertBindingGuard.decide(TP_A.toUpperCase(), TP_A, Policy.ALLOW_LEGACY_UNBOUND));
    }

    @Test
    void boundTokenWithDifferentPresentedIsMismatchUnderBothPolicies() {
        // the legacy flag must NEVER relax a bound token's exact-match requirement
        assertEquals(Decision.MISMATCH, CertBindingGuard.decide(TP_A, TP_B, Policy.REQUIRE_BOUND));
        assertEquals(Decision.MISMATCH, CertBindingGuard.decide(TP_A, TP_B, Policy.ALLOW_LEGACY_UNBOUND));
        assertFalse(CertBindingGuard.decide(TP_A, TP_B, Policy.ALLOW_LEGACY_UNBOUND).satisfied());
    }

    @Test
    void boundTokenWithMissingPresentedIsRejectedUnderBothPolicies() {
        assertEquals(Decision.PRESENTED_MISSING, CertBindingGuard.decide(TP_A, null, Policy.REQUIRE_BOUND));
        assertEquals(Decision.PRESENTED_MISSING, CertBindingGuard.decide(TP_A, "", Policy.ALLOW_LEGACY_UNBOUND));
        assertEquals(Decision.PRESENTED_MISSING, CertBindingGuard.decide(TP_A, "  ", Policy.ALLOW_LEGACY_UNBOUND));
    }

    @Test
    void boundTokenWithMalformedPresentedFailsClosed() {
        // non-hex / odd-length presented values can never match (CertThumbprint hex-decode fail-closed)
        assertEquals(Decision.MISMATCH,
                CertBindingGuard.decide(TP_A, "zz".repeat(32), Policy.REQUIRE_BOUND));
        assertEquals(Decision.MISMATCH,
                CertBindingGuard.decide(TP_A, TP_A.substring(1), Policy.REQUIRE_BOUND));
    }

    @Test
    void unboundTokenIsRejectedByDefaultPolicy() {
        // check-list #3: no pass for a legacy-unbound token without the explicit allow flag
        assertEquals(Decision.UNBOUND_REJECTED, CertBindingGuard.decide(null, TP_A, Policy.REQUIRE_BOUND));
        assertEquals(Decision.UNBOUND_REJECTED, CertBindingGuard.decide(" ", null, Policy.REQUIRE_BOUND));
        assertFalse(CertBindingGuard.decide(null, null, Policy.REQUIRE_BOUND).satisfied());
    }

    @Test
    void unboundTokenPassesOnlyUnderExplicitLegacyAllow() {
        assertEquals(Decision.UNBOUND_ALLOWED, CertBindingGuard.decide(null, null, Policy.ALLOW_LEGACY_UNBOUND));
        assertEquals(Decision.UNBOUND_ALLOWED, CertBindingGuard.decide("", TP_A, Policy.ALLOW_LEGACY_UNBOUND));
        assertTrue(CertBindingGuard.decide(null, null, Policy.ALLOW_LEGACY_UNBOUND).satisfied());
    }

    @Test
    void nullPolicyIsCoercedToFailClosedRequireBound() {
        assertEquals(Decision.UNBOUND_REJECTED, CertBindingGuard.decide(null, TP_A, null));
        assertEquals(Decision.BOUND_MATCH, CertBindingGuard.decide(TP_A, TP_A, null));
    }
}
