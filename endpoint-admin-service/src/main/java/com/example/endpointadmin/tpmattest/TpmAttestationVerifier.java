package com.example.endpointadmin.tpmattest;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;

/**
 * Faz 22.3B (ADR-0039) gate-4a-2.2 — the certify (V4) and quote (V5) crypto checks,
 * design §4/§10.5. <b>Disabled-by-default</b> (wired at gate-4d behind the feature flag).
 *
 * <p>Both verify an AK signature over a {@code TPMS_ATTEST}. The JCA algorithm is derived
 * from the {@link TpmtSignature}'s own {@code sigAlg}/{@code hashAlg}, then cross-checked
 * against the AK's restricted scheme and key family (V12 / T-3 algorithm-confusion). RSAPSS
 * uses an explicit {@link PSSParameterSpec} (salt = digest length) rather than relying on the
 * algorithm string (provider-portability — Codex review). Every failure is fail-closed and
 * carries an audit-only {@link TpmDenyCode}.
 *
 * <ul>
 *   <li><b>V4</b> {@code KEY_NOT_TPM_BOUND}: {@code certifySig} valid over {@code certifyAttest}
 *       by the AK; attest is a {@code TPM2_Certify}; the certified Name equals the device key's
 *       recomputed Name; the device key is TPM-resident + non-exportable (fixedTPM ∧
 *       sensitiveDataOrigin ∧ ¬restricted leaf).</li>
 *   <li><b>V5</b> {@code QUOTE_INVALID}: {@code quoteSig} valid over {@code quoteAttest} by the AK;
 *       attest is a {@code TPM2_Quote}; {@code extraData} equals the issued nonce (raw bytes,
 *       constant-time). PCR-digest policy is V6 (a later slice).</li>
 * </ul>
 * The device key's <i>size</i> floor (RSA-3072+) is V9 (CSR key policy, gate-4d via
 * {@link TpmAlgorithmPolicy.Role#DEVICE}); V4 proves the <i>binding</i>, not the size.
 */
public final class TpmAttestationVerifier {

    private TpmAttestationVerifier() {}

    /** V4 — prove the device key is the TPM-resident key the AK certified. */
    public static void verifyCertify(TpmPublicArea ak, byte[] certifyAttest, byte[] certifySig,
                                     TpmPublicArea deviceKey) {
        verifyAkSignature(ak, certifyAttest, certifySig, TpmDenyCode.KEY_NOT_TPM_BOUND);

        TpmsAttest attest = parseAttest(certifyAttest, TpmDenyCode.KEY_NOT_TPM_BOUND);
        if (!attest.isCertify()) {
            throw new TpmAttestException(TpmDenyCode.KEY_NOT_TPM_BOUND,
                    "attest is not a TPM2_Certify (type 0x" + Integer.toHexString(attest.type()) + ")");
        }
        byte[] expectedName = deviceKey.computeName();
        if (!TpmsAttest.constantTimeEquals(attest.certifiedName(), expectedName)) {
            throw new TpmAttestException(TpmDenyCode.KEY_NOT_TPM_BOUND,
                    "certified Name != device key Name (CSR key is not the certified TPM key)");
        }
        // Non-exportable, TPM-resident leaf (V4 binding semantics).
        if (!deviceKey.isFixedTpm() || !deviceKey.isSensitiveDataOrigin()) {
            throw new TpmAttestException(TpmDenyCode.KEY_NOT_TPM_BOUND,
                    "device key is not TPM-resident/non-exportable (fixedTPM ∧ sensitiveDataOrigin)");
        }
        if (deviceKey.isRestricted()) {
            throw new TpmAttestException(TpmDenyCode.KEY_NOT_TPM_BOUND,
                    "device identity key must be an unrestricted leaf key, not a restricted key");
        }
    }

    /** V5 — prove a fresh, AK-signed quote over the issued nonce. */
    public static void verifyQuote(TpmPublicArea ak, byte[] quoteAttest, byte[] quoteSig,
                                   byte[] expectedNonce) {
        verifyAkSignature(ak, quoteAttest, quoteSig, TpmDenyCode.QUOTE_INVALID);

        TpmsAttest attest = parseAttest(quoteAttest, TpmDenyCode.QUOTE_INVALID);
        if (!attest.isQuote()) {
            throw new TpmAttestException(TpmDenyCode.QUOTE_INVALID,
                    "attest is not a TPM2_Quote (type 0x" + Integer.toHexString(attest.type()) + ")");
        }
        if (expectedNonce == null || expectedNonce.length == 0) {
            throw new TpmAttestException(TpmDenyCode.QUOTE_INVALID, "no issued nonce to bind the quote to");
        }
        if (!TpmsAttest.constantTimeEquals(attest.extraData(), expectedNonce)) {
            throw new TpmAttestException(TpmDenyCode.QUOTE_INVALID,
                    "quote extraData != issued nonce (replay / stale-nonce)");
        }
    }

    // ───────────────────────────── shared signature verification ─────────────────────────────

    private static void verifyAkSignature(TpmPublicArea ak, byte[] signedBytes, byte[] tpmtSig,
                                          TpmDenyCode failCode) {
        TpmtSignature sig;
        try {
            sig = TpmtSignature.parse(tpmtSig);
        } catch (IllegalArgumentException e) {
            throw new TpmAttestException(failCode, "malformed TPMT_SIGNATURE: " + e.getMessage(), e);
        }
        PublicKey akKey = ak.toPublicKey();

        // V12 algorithm whitelist on the AK + the signature.
        TpmAlgorithmPolicy.requireStrongHash(sig.hashAlg());
        TpmAlgorithmPolicy.requireSchemeMatchesKey(sig.sigAlg(), akKey);
        TpmAlgorithmPolicy.requireKeyMeetsPolicy(akKey, TpmAlgorithmPolicy.Role.AK);
        // The signature scheme must match the AK's intrinsic restricted scheme (anti-substitution).
        int akScheme = ak.signingSchemeAlg();
        if (akScheme != TpmPublicArea.ALG_NULL && akScheme != sig.sigAlg()) {
            throw new TpmAttestException(TpmDenyCode.WEAK_ALGORITHM,
                    "signature scheme 0x" + Integer.toHexString(sig.sigAlg())
                            + " != AK restricted scheme 0x" + Integer.toHexString(akScheme));
        }

        byte[] sigBytes = sig.isRsa() ? sig.rsaSignature() : sig.ecdsaDerSignature();
        boolean ok;
        try {
            ok = rawVerify(akKey, sig.sigAlg(), sig.hashAlg(), signedBytes, sigBytes);
        } catch (GeneralSecurityException e) {
            throw new TpmAttestException(failCode, "AK signature verification error", e);
        }
        if (!ok) {
            throw new TpmAttestException(failCode, "AK signature did not verify over the attest bytes");
        }
    }

    /**
     * Raw JCA verify for a TPM signature scheme. Package-private so the RSAPSS / ECDSA
     * branches (which the RSASSA swtpm golden vector does not exercise) are unit-tested
     * directly against real JCA-generated signatures.
     */
    static boolean rawVerify(PublicKey key, int sigAlg, int hashAlg, byte[] data, byte[] sigBytes)
            throws GeneralSecurityException {
        Signature verifier = jcaVerifier(sigAlg, hashAlg, key);
        verifier.initVerify(key);
        verifier.update(data);
        return verifier.verify(sigBytes);
    }

    private static TpmsAttest parseAttest(byte[] raw, TpmDenyCode failCode) {
        try {
            return TpmsAttest.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new TpmAttestException(failCode, "malformed TPMS_ATTEST: " + e.getMessage(), e);
        }
    }

    /** Build a JCA {@link Signature} from the TPM sig/hash algs. RSAPSS gets an explicit PSS spec. */
    private static Signature jcaVerifier(int sigAlg, int hashAlg, PublicKey key) throws GeneralSecurityException {
        String digestJca = jcaDigest(hashAlg);   // "SHA-256"
        String digestTok = digestJca.replace("-", ""); // "SHA256"
        return switch (sigAlg) {
            case TpmPublicArea.ALG_RSASSA -> Signature.getInstance(digestTok + "withRSA");
            case TpmPublicArea.ALG_ECDSA -> Signature.getInstance(digestTok + "withECDSA");
            case TpmPublicArea.ALG_RSAPSS -> {
                Signature s = Signature.getInstance("RSASSA-PSS");
                int saltLen = switch (hashAlg) {
                    case TpmPublicArea.ALG_SHA256 -> 32;
                    case TpmPublicArea.ALG_SHA384 -> 48;
                    case TpmPublicArea.ALG_SHA512 -> 64;
                    default -> throw new GeneralSecurityException("unsupported PSS hash 0x" + Integer.toHexString(hashAlg));
                };
                s.setParameter(new PSSParameterSpec(digestJca, "MGF1",
                        new MGF1ParameterSpec(digestJca), saltLen, 1));
                yield s;
            }
            default -> throw new GeneralSecurityException("unsupported sigAlg 0x" + Integer.toHexString(sigAlg));
        };
    }

    private static String jcaDigest(int tpmHashAlg) throws GeneralSecurityException {
        return switch (tpmHashAlg) {
            case TpmPublicArea.ALG_SHA256 -> "SHA-256";
            case TpmPublicArea.ALG_SHA384 -> "SHA-384";
            case TpmPublicArea.ALG_SHA512 -> "SHA-512";
            default -> throw new GeneralSecurityException("unsupported hash 0x" + Integer.toHexString(tpmHashAlg));
        };
    }
}
