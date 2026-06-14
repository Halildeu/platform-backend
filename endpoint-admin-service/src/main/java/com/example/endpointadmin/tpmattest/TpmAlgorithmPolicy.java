package com.example.endpointadmin.tpmattest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Faz 22.3B (ADR-0039) gate-4a-2.2 — verifier <b>V12</b> (algorithm/key-bits whitelist),
 * design §4/§10.5 + hardening T-3 (algorithm confusion).
 *
 * <p>Key-bits floor is <b>role-split</b> (3-AI consult: Codex {@code 019ec723} AGREE +
 * MiniMax) because real TPM 2.0 EKs ship RSA-2048 / EC-P256 and RSA-3072 EKs are rare —
 * a blanket RSA-3072 floor would reject real hardware:
 * <ul>
 *   <li><b>EK / AK</b> (manufacturer-/TPM-constrained): RSA-2048+ <i>or</i> EC-P256+.
 *       A 2048-bit RSA EK/AK is accepted but <b>WARN-logged</b> (telemetry, never silent).</li>
 *   <li><b>DEVICE</b> (the identity key whose CSR we sign via Vault PKI — our policy, strict):
 *       RSA-3072+ <i>or</i> EC-P256+; below floor → reject.</li>
 * </ul>
 * Hashes must be SHA-256+ everywhere; SHA-1/MD5 rejected. Signature scheme must be
 * RSASSA / RSAPSS / ECDSA, and the scheme family must match the key type (no RSA↔ECC
 * confusion). All violations fail-closed → {@link TpmDenyCode#WEAK_ALGORITHM}.
 */
public final class TpmAlgorithmPolicy {

    private static final Logger log = LoggerFactory.getLogger(TpmAlgorithmPolicy.class);

    public enum Role { EK, AK, DEVICE }

    private static final int RSA_FLOOR_CONSTRAINED = 2048; // EK/AK
    private static final int RSA_FLOOR_ISSUED = 3072;      // device/CSR key
    private static final int RSA_PREFERRED = 3072;

    private TpmAlgorithmPolicy() {}

    /** V12: validate a reconstructed public key's algorithm + size against the role's floor. */
    public static void requireKeyMeetsPolicy(PublicKey key, Role role) {
        if (key instanceof RSAPublicKey rsa) {
            int bits = rsa.getModulus().bitLength();
            int floor = (role == Role.DEVICE) ? RSA_FLOOR_ISSUED : RSA_FLOOR_CONSTRAINED;
            if (bits < floor) {
                throw weak(role + " RSA key is " + bits + " bits, below the " + floor + "-bit floor");
            }
            if ((role == Role.EK || role == Role.AK) && bits < RSA_PREFERRED) {
                log.warn("Faz22.3B V12: {} RSA key is {} bits (>= {} floor, < {} preferred) — accepted, telemetry-flagged",
                        role, bits, RSA_FLOOR_CONSTRAINED, RSA_PREFERRED);
            }
        } else if (key instanceof ECPublicKey ec) {
            int bits = ec.getParams().getCurve().getField().getFieldSize();
            if (bits < 256) {
                throw weak(role + " EC key field is " + bits + " bits, below P-256");
            }
        } else {
            throw weak(role + " key is neither RSA nor EC (" + (key == null ? "null" : key.getAlgorithm()) + ")");
        }
    }

    /** V12: hash must be SHA-256 or stronger (reject SHA-1/MD5/unknown). */
    public static void requireStrongHash(int tpmHashAlg) {
        switch (tpmHashAlg) {
            case TpmPublicArea.ALG_SHA256, TpmPublicArea.ALG_SHA384, TpmPublicArea.ALG_SHA512 -> { /* ok */ }
            default -> throw weak("hash alg 0x" + Integer.toHexString(tpmHashAlg) + " is not SHA-256+");
        }
    }

    /** V12: signature scheme must be a whitelisted one AND match the key family (anti-confusion). */
    public static void requireSchemeMatchesKey(int sigAlg, PublicKey key) {
        boolean rsaScheme = sigAlg == TpmPublicArea.ALG_RSASSA || sigAlg == TpmPublicArea.ALG_RSAPSS;
        boolean ecdsaScheme = sigAlg == TpmPublicArea.ALG_ECDSA;
        if (!rsaScheme && !ecdsaScheme) {
            throw weak("signature scheme 0x" + Integer.toHexString(sigAlg) + " is not whitelisted");
        }
        if (rsaScheme && !(key instanceof RSAPublicKey)) {
            throw weak("RSA signature scheme over a non-RSA key (algorithm confusion)");
        }
        if (ecdsaScheme && !(key instanceof ECPublicKey)) {
            throw weak("ECDSA signature scheme over a non-EC key (algorithm confusion)");
        }
    }

    /** V12: the key's intrinsic restricted scheme (from its TPMT_PUBLIC) must also be whitelisted, when set. */
    public static void requireIntrinsicSchemeWhitelisted(int schemeAlg, int schemeHashAlg) {
        if (schemeAlg == TpmPublicArea.ALG_NULL) {
            return; // an unrestricted key may carry a NULL scheme; the signature-time scheme is checked separately
        }
        if (schemeAlg != TpmPublicArea.ALG_RSASSA
                && schemeAlg != TpmPublicArea.ALG_RSAPSS
                && schemeAlg != TpmPublicArea.ALG_ECDSA) {
            throw weak("key scheme 0x" + Integer.toHexString(schemeAlg) + " is not whitelisted");
        }
        requireStrongHash(schemeHashAlg);
    }

    private static TpmAttestException weak(String detail) {
        return new TpmAttestException(TpmDenyCode.WEAK_ALGORITHM, detail);
    }
}
