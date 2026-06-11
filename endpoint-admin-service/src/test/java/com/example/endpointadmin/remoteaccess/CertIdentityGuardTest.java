package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.CertIdentityGuard.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 B1.4a-0 — {@link CertIdentityGuard} fail-closed issuer/serial identity truth table. */
class CertIdentityGuardTest {

    private static final String ISSUER = "CN=Agent Fleet CA,O=Acik,C=TR";
    private static final String OTHER_ISSUER = "CN=Rogue CA,O=Evil,C=XX";
    private static final String SERIAL = "0A1B2C3D";
    private static final String TP = "ab".repeat(32);

    /** An expected pin constraining only the issuer (the B1.4a-0 config shape). */
    private static CertRef expectedIssuer() {
        return new CertRef(null, "SHA-256", null, ISSUER);
    }

    /** A presented cert carrying the full identity triple. */
    private static CertRef presented(String issuer, String serial) {
        return new CertRef(TP, "SHA-256", serial, issuer);
    }

    @Test
    void nullExpectedIsNotEnforced() {
        assertEquals(Decision.NOT_ENFORCED, CertIdentityGuard.decide(null, presented(ISSUER, SERIAL)));
        assertTrue(Decision.NOT_ENFORCED.satisfied());
    }

    @Test
    void matchingIssuerSatisfies() {
        assertEquals(Decision.MATCH, CertIdentityGuard.decide(expectedIssuer(), presented(ISSUER, SERIAL)));
    }

    @Test
    void wrongIssuerIsMismatch() {
        var d = CertIdentityGuard.decide(expectedIssuer(), presented(OTHER_ISSUER, SERIAL));
        assertEquals(Decision.ISSUER_MISMATCH, d);
        assertFalse(d.satisfied());
    }

    @Test
    void missingPresentedIssuerUnderAPinIsFailClosed() {
        assertEquals(Decision.ISSUER_MISSING, CertIdentityGuard.decide(expectedIssuer(), presented(null, SERIAL)));
        assertEquals(Decision.ISSUER_MISSING, CertIdentityGuard.decide(expectedIssuer(), presented("  ", SERIAL)));
        assertEquals(Decision.ISSUER_MISSING, CertIdentityGuard.decide(expectedIssuer(), null));
    }

    @Test
    void issuerComparisonIsExactCaseSensitive() {
        // B1.4a-0 intentionally does NOT canonicalise the DN (real RFC 4514 normalisation = B1.4a PKIX);
        // an exact match is deterministic + strictly tighter than no check.
        assertEquals(Decision.ISSUER_MISMATCH,
                CertIdentityGuard.decide(expectedIssuer(), presented(ISSUER.toLowerCase(), SERIAL)));
    }

    @Test
    void serialIsEnforcedWhenExpectedSet() {
        // forward-wired: once a bound-token serial is pinned (B1.4a/store), a differing presented serial
        // is rejected even with the right issuer.
        var expectedBoth = new CertRef(null, "SHA-256", SERIAL, ISSUER);
        assertEquals(Decision.MATCH, CertIdentityGuard.decide(expectedBoth, presented(ISSUER, SERIAL)));
        assertEquals(Decision.SERIAL_MISMATCH, CertIdentityGuard.decide(expectedBoth, presented(ISSUER, "FFFF")));
        assertEquals(Decision.SERIAL_MISMATCH, CertIdentityGuard.decide(expectedBoth, presented(ISSUER, null)));
    }

    @Test
    void issuerTakesPrecedenceOverSerial() {
        // both wrong → the issuer (wrong CA) is the reported cause (evaluated first)
        var expectedBoth = new CertRef(null, "SHA-256", SERIAL, ISSUER);
        assertEquals(Decision.ISSUER_MISMATCH,
                CertIdentityGuard.decide(expectedBoth, presented(OTHER_ISSUER, "FFFF")));
    }

    @Test
    void onlySatisfiedDecisionsArePass() {
        for (Decision d : Decision.values()) {
            boolean expectPass = d == Decision.NOT_ENFORCED || d == Decision.MATCH;
            assertEquals(expectPass, d.satisfied(), d.name());
        }
    }
}
