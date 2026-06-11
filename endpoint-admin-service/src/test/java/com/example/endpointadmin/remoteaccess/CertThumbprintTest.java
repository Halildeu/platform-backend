package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 B1.1 — {@link CertThumbprint} SHA-256 compute + fail-closed constant-time compare. */
class CertThumbprintTest {

    @Test
    void ofDerIsDeterministicLowercaseHex64() {
        byte[] der = "fake-cert-der".getBytes(StandardCharsets.US_ASCII);
        String t1 = CertThumbprint.ofDer(der);
        assertEquals(64, t1.length());
        assertTrue(t1.matches("[0-9a-f]{64}"), "lowercase hex");
        assertEquals(t1, CertThumbprint.ofDer(der), "deterministic");
    }

    @Test
    void ofDerMatchesKnownSha256Vector() {
        // SHA-256("abc") is a published test vector — proves we hash the DER bytes correctly.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CertThumbprint.ofDer("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void ofDerNullOrEmptyIsNull() {
        assertNull(CertThumbprint.ofDer(null));
        assertNull(CertThumbprint.ofDer(new byte[0]));
    }

    @Test
    void matchesTrueForEqual() {
        String t = CertThumbprint.ofDer("x".getBytes(StandardCharsets.US_ASCII));
        assertTrue(CertThumbprint.matches(t, t));
    }

    @Test
    void matchesFailsClosedOnNullBlankMismatchAndLength() {
        assertFalse(CertThumbprint.matches(null, "a"));
        assertFalse(CertThumbprint.matches("a", null));
        assertFalse(CertThumbprint.matches("", "a"));
        assertFalse(CertThumbprint.matches("a", "  "));
        assertFalse(CertThumbprint.matches("aaaa", "bbbb"));
        assertFalse(CertThumbprint.matches("aaaa", "aaaaaa")); // different length → no match, no exception
    }

    @Test
    void matchesIsCaseInsensitiveOverHex() {
        // the bound thumbprint is stored lowercase; a presented uppercase hex of the SAME cert must match.
        String lower = CertThumbprint.ofDer("cert".getBytes(StandardCharsets.US_ASCII));
        assertTrue(CertThumbprint.matches(lower, lower.toUpperCase()));
    }

    @Test
    void matchesRejectsNonHexAndOddLength() {
        assertFalse(CertThumbprint.matches("zzzz", "zzzz")); // non-hex chars → fail-closed
        assertFalse(CertThumbprint.matches("abc", "abc"));   // odd length → fail-closed
    }

    @Test
    void isPresentDistinguishesBound() {
        assertTrue(CertThumbprint.isPresent("abc"));
        assertFalse(CertThumbprint.isPresent(null));
        assertFalse(CertThumbprint.isPresent("  "));
    }
}
