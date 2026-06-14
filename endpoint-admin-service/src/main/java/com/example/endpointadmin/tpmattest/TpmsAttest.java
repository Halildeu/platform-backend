package com.example.endpointadmin.tpmattest;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Faz 22.3B (ADR-0039) gate-4a-2.2 — canonical parser for a {@code TPMS_ATTEST}
 * (TCG TPM 2.0 Part 2, Table "TPMS_ATTEST"), the structure a TPM signs in
 * {@code TPM2_Certify} (V4) and {@code TPM2_Quote} (V5).
 *
 * <p>Big-endian, explicit-length, bounds-checked, and consumed <b>exactly</b> —
 * trailing or short bytes are rejected (hardening T-9, quote-struct malleability).
 *
 * <pre>
 * TPMS_ATTEST := magic(UINT32 == TPM_GENERATED 0xFF544347)
 *              ‖ type(UINT16, TPMI_ST_ATTEST)
 *              ‖ qualifiedSigner(TPM2B_NAME)
 *              ‖ extraData(TPM2B_DATA)            // the verifier nonce for QUOTE
 *              ‖ clockInfo(TPMS_CLOCK_INFO = 17 bytes)
 *              ‖ firmwareVersion(UINT64)
 *              ‖ attested(TPMU_ATTEST)            // depends on type
 *   CERTIFY (0x8017): TPMS_CERTIFY_INFO = name(TPM2B_NAME) ‖ qualifiedName(TPM2B_NAME)
 *   QUOTE   (0x8018): TPMS_QUOTE_INFO  = pcrSelect(TPML_PCR_SELECTION) ‖ pcrDigest(TPM2B_DIGEST)
 * </pre>
 */
public final class TpmsAttest {

    public static final long TPM_GENERATED = 0xFF544347L;
    public static final int ST_ATTEST_CERTIFY = 0x8017;
    public static final int ST_ATTEST_QUOTE = 0x8018;

    private final int type;
    private final byte[] qualifiedSigner;
    private final byte[] extraData;
    private final byte[] certifiedName; // CERTIFY: the attested object's Name; null otherwise

    private TpmsAttest(int type, byte[] qualifiedSigner, byte[] extraData, byte[] certifiedName) {
        this.type = type;
        this.qualifiedSigner = qualifiedSigner;
        this.extraData = extraData;
        this.certifiedName = certifiedName;
    }

    public static TpmsAttest parse(byte[] raw) {
        if (raw == null) {
            throw new IllegalArgumentException("attest bytes required");
        }
        ByteBuffer bb = ByteBuffer.wrap(raw); // big-endian
        long magic = u32(bb);
        if (magic != TPM_GENERATED) {
            throw new IllegalArgumentException("not TPM_GENERATED (magic=0x" + Long.toHexString(magic) + ")");
        }
        int type = u16(bb);
        byte[] qualifiedSigner = readTpm2b(bb, "qualifiedSigner");
        byte[] extraData = readTpm2b(bb, "extraData");
        skip(bb, 17, "clockInfo"); // clock(8) + resetCount(4) + restartCount(4) + safe(1)
        skip(bb, 8, "firmwareVersion");

        byte[] certifiedName = null;
        if (type == ST_ATTEST_CERTIFY) {
            certifiedName = readTpm2b(bb, "certifyInfo.name");
            readTpm2b(bb, "certifyInfo.qualifiedName"); // consume
        } else if (type == ST_ATTEST_QUOTE) {
            long count = u32(bb); // TPML_PCR_SELECTION.count
            if (count < 0 || count > 16) {
                throw new IllegalArgumentException("implausible PCR selection count " + count);
            }
            for (long i = 0; i < count; i++) {
                u16(bb); // hashAlg
                int sizeofSelect = u8(bb);
                skip(bb, sizeofSelect, "pcrSelect.bitmap");
            }
            readTpm2b(bb, "quoteInfo.pcrDigest"); // consume
        } else {
            throw new IllegalArgumentException("unsupported attest type 0x" + Integer.toHexString(type));
        }
        if (bb.hasRemaining()) {
            throw new IllegalArgumentException("trailing bytes in TPMS_ATTEST (" + bb.remaining() + " left)");
        }
        return new TpmsAttest(type, qualifiedSigner, extraData, certifiedName);
    }

    public int type() { return type; }
    public boolean isCertify() { return type == ST_ATTEST_CERTIFY; }
    public boolean isQuote() { return type == ST_ATTEST_QUOTE; }
    public byte[] qualifiedSigner() { return qualifiedSigner.clone(); }
    public byte[] extraData() { return extraData.clone(); }

    /** The certified object's TPM Name (CERTIFY only). For V4: must equal the device key's recomputed Name. */
    public byte[] certifiedName() {
        if (certifiedName == null) {
            throw new IllegalStateException("certifiedName only present for TPM2_Certify attestations");
        }
        return certifiedName.clone();
    }

    private static int u8(ByteBuffer bb) {
        if (bb.remaining() < 1) throw new IllegalArgumentException("truncated UINT8");
        return bb.get() & 0xFF;
    }
    private static int u16(ByteBuffer bb) {
        if (bb.remaining() < 2) throw new IllegalArgumentException("truncated UINT16");
        return bb.getShort() & 0xFFFF;
    }
    private static long u32(ByteBuffer bb) {
        if (bb.remaining() < 4) throw new IllegalArgumentException("truncated UINT32");
        return bb.getInt() & 0xFFFFFFFFL;
    }
    private static void skip(ByteBuffer bb, int n, String what) {
        if (n < 0 || n > bb.remaining()) throw new IllegalArgumentException(what + " overruns buffer");
        bb.position(bb.position() + n);
    }
    private static byte[] readTpm2b(ByteBuffer bb, String what) {
        int n = u16(bb);
        if (n > bb.remaining()) throw new IllegalArgumentException("TPM2B " + what + " overruns buffer");
        byte[] b = new byte[n];
        bb.get(b);
        return b;
    }

    /** Constant-time compare for the nonce/name equality checks (avoid early-exit timing). */
    static boolean constantTimeEquals(byte[] a, byte[] b) {
        return java.security.MessageDigest.isEqual(a, b);
    }

    @Override
    public String toString() {
        return "TpmsAttest{type=0x" + Integer.toHexString(type)
                + ", extraData=" + extraData.length + "B"
                + (certifiedName != null ? ", certifiedName=" + Arrays.hashCode(certifiedName) : "") + "}";
    }
}
