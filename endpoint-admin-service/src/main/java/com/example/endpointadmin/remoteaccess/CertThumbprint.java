package com.example.endpointadmin.remoteaccess;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Faz 22.6 B1.1 — certificate thumbprint primitive for mTLS-bound session tokens (RFC 8705-style
 * proof-of-possession; Codex 019eb54b B1 plan). The thumbprint is the <b>SHA-256 of the DER-encoded
 * certificate</b>, rendered as lowercase hex (64 chars). RFC 8705 §3.1's {@code x5t#S256} is the
 * base64url form of the SAME hash; we store hex for a readable {@code CHAR(64)} column + trivial
 * equality. Pure + side-effect-free; the real TLS layer that extracts the presented client cert is the
 * B1.4 transport seam — here we only compute + compare, so the binding logic is testable without TLS.
 *
 * <p><b>Fail-closed comparison:</b> {@link #matches} is null/blank-safe (any missing side → {@code false})
 * and constant-time (no early-exit on the first differing byte → no timing oracle on the bound value).
 */
public final class CertThumbprint {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CertThumbprint() {
    }

    /**
     * Compute the lowercase-hex SHA-256 thumbprint of a DER-encoded certificate.
     *
     * @param der the DER bytes of the certificate ({@code X509Certificate.getEncoded()})
     * @return 64-char lowercase hex, or {@code null} if {@code der} is null/empty (caller fails closed)
     */
    public static String ofDer(byte[] der) {
        if (der == null || der.length == 0) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS platform spec — unreachable; fail closed if it ever isn't.
            return null;
        }
    }

    /**
     * Constant-time, fail-closed equality of two hex thumbprints. Returns {@code false} if either side is
     * null/blank (an unbound token never "matches" a presented cert) or the lengths differ; otherwise a
     * length-independent byte compare so the bound thumbprint is not leaked through timing.
     */
    public static boolean matches(String a, String b) {
        byte[] da = decodeHex(a);
        byte[] db = decodeHex(b);
        if (da == null || db == null) {
            return false; // fail-closed: blank / odd-length / non-hex on either side never matches
        }
        // constant-time + length-safe over the DECODED bytes, so case ('AB' == 'ab') and format are
        // normalized (Codex 019eb54b B1.1a REVISE #1 — the presented thumbprint from the TLS layer may
        // not be lowercase, and a non-hex payload must never match rather than slip through as a string).
        return MessageDigest.isEqual(da, db);
    }

    /** Decode a hex string to bytes (case-insensitive); null on null/blank/odd-length/non-hex (fail-closed). */
    private static byte[] decodeHex(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        int n = t.length();
        if (n == 0 || (n & 1) == 1) {
            return null;
        }
        byte[] out = new byte[n / 2];
        for (int i = 0; i < n; i += 2) {
            int hi = hexVal(t.charAt(i));
            int lo = hexVal(t.charAt(i + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** Whether a stored thumbprint value represents an actual binding (present + non-blank). */
    public static boolean isPresent(String thumbprint) {
        return thumbprint != null && !thumbprint.isBlank();
    }
}
