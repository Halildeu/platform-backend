package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 B1.1 — {@link CertBoundToken} fail-closed jti↔cert binding semantics. */
class CertBoundTokenTest {

    @Test
    void boundTokenMatchesExactThumbprint() {
        CertBoundToken tok = new CertBoundToken("jti-1", "abc123");
        assertTrue(tok.isBound());
        assertTrue(tok.bindingMatches("abc123"));
    }

    @Test
    void boundTokenRejectsMismatchOrMissingPresented() {
        CertBoundToken tok = new CertBoundToken("jti-1", "abc123");
        assertFalse(tok.bindingMatches("def456"));
        assertFalse(tok.bindingMatches(null));
        assertFalse(tok.bindingMatches("  "));
    }

    @Test
    void unboundTokenIsNotBoundAndNeverMatches() {
        assertFalse(new CertBoundToken("jti", null).isBound());
        assertFalse(new CertBoundToken("jti", "  ").isBound());
        // fail-closed: an unbound token does NOT auto-pass even with a presented cert — the legacy-allow
        // decision is the runtime's (B1.1c), never an implicit match here.
        assertFalse(new CertBoundToken("jti", null).bindingMatches("anything"));
        assertFalse(new CertBoundToken("jti", null).bindingMatches(null));
    }
}
