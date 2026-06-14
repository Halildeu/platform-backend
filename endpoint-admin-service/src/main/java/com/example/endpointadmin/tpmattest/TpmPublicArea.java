package com.example.endpointadmin.tpmattest;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Faz 22.3B (ADR-0039) gate-4 — minimal parser for a TPM2B_PUBLIC / TPMT_PUBLIC
 * (verifier V11, design §4 / §10.5).
 *
 * <p><b>Why hand-parsed (no attestation library):</b> the verifier needs only a
 * few fixed-offset fields — {@code nameAlg}, {@code objectAttributes}, and the
 * marshaled {@code TPMT_PUBLIC} bytes for the TPM Name. A WebAuthn library would
 * expose its TPM structures only via internal CBOR deserializers (not a
 * standalone byte→object API), so those fields are parsed directly here and the
 * library dependency is dropped. Correctness is pinned by the swtpm golden
 * vector: {@link #computeName()} MUST equal the TPM-emitted {@code ak.name}
 * (asserted in {@code TpmPublicAreaTest} / {@code TpmGoldenVectorTest}). Only the
 * header this verifier needs is parsed; parameters/unique are not unmarshaled.
 *
 * <p>TCG TPM 2.0 Part 2: TPMT_PUBLIC = type(UINT16) ‖ nameAlg(UINT16) ‖
 * objectAttributes(UINT32) ‖ authPolicy(TPM2B) ‖ parameters ‖ unique.
 * The TPM Name = UINT16(nameAlg) ‖ H_nameAlg(pubArea).
 */
public final class TpmPublicArea {

    // TPMI_ALG_HASH
    public static final int ALG_SHA256 = 0x000B;
    public static final int ALG_SHA384 = 0x000C;
    public static final int ALG_SHA512 = 0x000D;
    // TPMI_ALG_PUBLIC
    public static final int ALG_RSA = 0x0001;
    public static final int ALG_ECC = 0x0023;
    // TPMA_OBJECT bits (TCG Part 2, Table 31)
    public static final int OBJ_FIXED_TPM = 1 << 1;
    public static final int OBJ_FIXED_PARENT = 1 << 4;
    public static final int OBJ_SENSITIVE_DATA_ORIGIN = 1 << 5;
    public static final int OBJ_RESTRICTED = 1 << 16;
    public static final int OBJ_DECRYPT = 1 << 17;
    public static final int OBJ_SIGN = 1 << 18;

    private final int type;
    private final int nameAlg;
    private final int objectAttributes;
    private final byte[] pubArea; // the TPMT_PUBLIC bytes (without any TPM2B size prefix)

    private TpmPublicArea(int type, int nameAlg, int objectAttributes, byte[] pubArea) {
        this.type = type;
        this.nameAlg = nameAlg;
        this.objectAttributes = objectAttributes;
        this.pubArea = pubArea;
    }

    /**
     * Parse a TPM public area. The wire format is <b>explicit</b> — the parser does
     * not heuristically sniff it:
     * <ul>
     *   <li>{@code isTpm2b == true} — input is a {@code TPM2B_PUBLIC}: a UINT16 size
     *       prefix followed by the {@code TPMT_PUBLIC}. The size field MUST equal the
     *       remaining length (validated; a mismatch is rejected). This is what the
     *       {@code tpm2_* -u} outputs and the {@code /attest} wire DTO carry.</li>
     *   <li>{@code isTpm2b == false} — input is a bare {@code TPMT_PUBLIC}.</li>
     * </ul>
     * The caller (the gate-4d {@code /attest} decoder) knows the on-wire format and
     * passes it; this removes the earlier ambiguous {@code size == len-2} heuristic.
     */
    public static TpmPublicArea parse(byte[] raw, boolean isTpm2b) {
        if (raw == null) {
            throw new IllegalArgumentException("public area bytes required");
        }
        byte[] pa;
        if (isTpm2b) {
            if (raw.length < 2) {
                throw new IllegalArgumentException("TPM2B_PUBLIC too short for its size prefix");
            }
            int declared = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
            if (declared != raw.length - 2) {
                throw new IllegalArgumentException(
                        "TPM2B_PUBLIC size mismatch: declared=" + declared + " actual=" + (raw.length - 2));
            }
            pa = new byte[raw.length - 2];
            System.arraycopy(raw, 2, pa, 0, pa.length);
        } else {
            pa = raw.clone();
        }
        if (pa.length < 8) {
            throw new IllegalArgumentException("TPMT_PUBLIC too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(pa);
        int type = bb.getShort() & 0xFFFF;
        int nameAlg = bb.getShort() & 0xFFFF;
        int attrs = bb.getInt();
        return new TpmPublicArea(type, nameAlg, attrs, pa);
    }

    public int type() { return type; }
    public int nameAlg() { return nameAlg; }
    public byte[] pubArea() { return pubArea.clone(); }

    public boolean isRestricted() { return (objectAttributes & OBJ_RESTRICTED) != 0; }
    public boolean isSign() { return (objectAttributes & OBJ_SIGN) != 0; }
    public boolean isDecrypt() { return (objectAttributes & OBJ_DECRYPT) != 0; }
    public boolean isFixedTpm() { return (objectAttributes & OBJ_FIXED_TPM) != 0; }
    public boolean isFixedParent() { return (objectAttributes & OBJ_FIXED_PARENT) != 0; }
    public boolean isSensitiveDataOrigin() { return (objectAttributes & OBJ_SENSITIVE_DATA_ORIGIN) != 0; }

    /**
     * V11: a trustworthy AK is a TPM-resident, TPM-generated <b>restricted signing</b>
     * key that cannot also decrypt — {@code restricted ∧ sign ∧ ¬decrypt ∧ fixedTPM ∧
     * fixedParent ∧ sensitiveDataOrigin} (TCG TPMA_OBJECT). The {@code ¬decrypt} and
     * {@code fixedParent} requirements tighten the AK semantics so a key that merely
     * sets restricted+sign (but is migratable or also a decryption key) cannot pass.
     */
    public boolean isRestrictedSigningKey() {
        return isRestricted() && isSign() && !isDecrypt()
                && isFixedTpm() && isFixedParent() && isSensitiveDataOrigin();
    }

    /** The TPM Name = UINT16(nameAlg) ‖ H_nameAlg(pubArea). Compared to the TPM-emitted ak.name (V11). */
    public byte[] computeName() {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance(jcaHash(nameAlg)).digest(pubArea);
        } catch (Exception e) {
            throw new IllegalStateException("unsupported nameAlg 0x" + Integer.toHexString(nameAlg), e);
        }
        byte[] name = new byte[2 + digest.length];
        name[0] = (byte) (nameAlg >> 8);
        name[1] = (byte) (nameAlg & 0xFF);
        System.arraycopy(digest, 0, name, 2, digest.length);
        return name;
    }

    public String computeNameHex() {
        return HexFormat.of().formatHex(computeName());
    }

    static String jcaHash(int alg) {
        return switch (alg) {
            case ALG_SHA256 -> "SHA-256";
            case ALG_SHA384 -> "SHA-384";
            case ALG_SHA512 -> "SHA-512";
            default -> throw new IllegalArgumentException("unsupported/disallowed nameAlg 0x" + Integer.toHexString(alg));
        };
    }
}
