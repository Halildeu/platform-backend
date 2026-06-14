package com.example.endpointadmin.tpmattest;

import java.nio.ByteBuffer;

/**
 * Faz 22.3B (ADR-0039) gate-4a-2.2 — parser for a {@code TPMT_SIGNATURE}
 * (TCG TPM 2.0 Part 2). The signature carries its OWN {@code sigAlg}/{@code hashAlg}
 * header; the verifier derives the JCA algorithm from THIS structure and then
 * cross-checks it against the signing key's restricted scheme (anti
 * algorithm-confusion — Codex review). Big-endian, exact-consume.
 *
 * <pre>
 * TPMT_SIGNATURE := sigAlg(UINT16, TPMI_ALG_SIG_SCHEME)
 *   RSASSA/RSAPSS: ‖ hashAlg(UINT16) ‖ sig(TPM2B_PUBLIC_KEY_RSA)
 *   ECDSA:         ‖ hashAlg(UINT16) ‖ signatureR(TPM2B_ECC_PARAMETER) ‖ signatureS(TPM2B_ECC_PARAMETER)
 * </pre>
 */
public final class TpmtSignature {

    private final int sigAlg;
    private final int hashAlg;
    private final byte[] rsaSig;  // RSASSA/RSAPSS
    private final byte[] eccR;    // ECDSA
    private final byte[] eccS;

    private TpmtSignature(int sigAlg, int hashAlg, byte[] rsaSig, byte[] eccR, byte[] eccS) {
        this.sigAlg = sigAlg;
        this.hashAlg = hashAlg;
        this.rsaSig = rsaSig;
        this.eccR = eccR;
        this.eccS = eccS;
    }

    public static TpmtSignature parse(byte[] raw) {
        if (raw == null) {
            throw new IllegalArgumentException("signature bytes required");
        }
        ByteBuffer bb = ByteBuffer.wrap(raw); // big-endian
        int sigAlg = u16(bb);
        int hashAlg = u16(bb);
        switch (sigAlg) {
            case TpmPublicArea.ALG_RSASSA, TpmPublicArea.ALG_RSAPSS -> {
                byte[] sig = readTpm2b(bb, "rsa.sig");
                requireConsumed(bb);
                return new TpmtSignature(sigAlg, hashAlg, sig, null, null);
            }
            case TpmPublicArea.ALG_ECDSA -> {
                byte[] r = readTpm2b(bb, "ecc.r");
                byte[] s = readTpm2b(bb, "ecc.s");
                requireConsumed(bb);
                return new TpmtSignature(sigAlg, hashAlg, null, r, s);
            }
            default -> throw new IllegalArgumentException("unsupported sigAlg 0x" + Integer.toHexString(sigAlg));
        }
    }

    public int sigAlg() { return sigAlg; }
    public int hashAlg() { return hashAlg; }
    public boolean isRsa() { return sigAlg == TpmPublicArea.ALG_RSASSA || sigAlg == TpmPublicArea.ALG_RSAPSS; }
    public boolean isEcdsa() { return sigAlg == TpmPublicArea.ALG_ECDSA; }
    public byte[] rsaSignature() { return rsaSig == null ? null : rsaSig.clone(); }

    /**
     * The DER {@code ECDSA-Sig-Value ::= SEQUENCE { r INTEGER, s INTEGER }} JCA expects,
     * built from the TPM's raw fixed-width R‖S. (Both integers are unsigned/positive.)
     */
    public byte[] ecdsaDerSignature() {
        if (eccR == null || eccS == null) {
            throw new IllegalStateException("not an ECDSA signature");
        }
        byte[] r = derInt(eccR);
        byte[] s = derInt(eccS);
        int len = r.length + s.length;
        // SEQUENCE; length is small (<128 for P-256/P-384), single-byte length form
        byte[] out = new byte[2 + len];
        out[0] = 0x30;
        out[1] = (byte) len;
        System.arraycopy(r, 0, out, 2, r.length);
        System.arraycopy(s, 0, out, 2 + r.length, s.length);
        return out;
    }

    private static byte[] derInt(byte[] magnitude) {
        // strip leading zeros
        int i = 0;
        while (i < magnitude.length - 1 && magnitude[i] == 0) i++;
        byte[] m = java.util.Arrays.copyOfRange(magnitude, i, magnitude.length);
        boolean pad = (m[0] & 0x80) != 0; // prepend 0x00 if high bit set (keep positive)
        byte[] v = new byte[2 + (pad ? 1 : 0) + m.length];
        v[0] = 0x02; // INTEGER
        v[1] = (byte) ((pad ? 1 : 0) + m.length);
        int off = 2;
        if (pad) v[off++] = 0x00;
        System.arraycopy(m, 0, v, off, m.length);
        return v;
    }

    private static int u16(ByteBuffer bb) {
        if (bb.remaining() < 2) throw new IllegalArgumentException("truncated UINT16");
        return bb.getShort() & 0xFFFF;
    }
    private static byte[] readTpm2b(ByteBuffer bb, String what) {
        int n = u16(bb);
        if (n > bb.remaining()) throw new IllegalArgumentException("TPM2B " + what + " overruns buffer");
        byte[] b = new byte[n];
        bb.get(b);
        return b;
    }
    private static void requireConsumed(ByteBuffer bb) {
        if (bb.hasRemaining()) {
            throw new IllegalArgumentException("trailing bytes in TPMT_SIGNATURE (" + bb.remaining() + " left)");
        }
    }
}
