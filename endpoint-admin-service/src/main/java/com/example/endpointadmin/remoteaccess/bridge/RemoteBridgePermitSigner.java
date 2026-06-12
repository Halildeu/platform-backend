package com.example.endpointadmin.remoteaccess.bridge;

import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.contract.WireContract;

import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.util.Base64;
import java.util.Optional;

/**
 * Faz 22.6 T-1b — the broker's {@link OperationPermit} signer. A DEDICATED ASYMMETRIC key (broker-private /
 * agent-public, e.g. ECDSA P-256), NOT the recorder/anchor HMAC key (Codex 019eb9fb): the agent only VERIFIES
 * a permit, it cannot MINT one, so a compromised (semi-trusted) endpoint cannot forge local authorization. The
 * signature covers {@link OperationPermit#canonicalPayload()} (the stable security fields, independent of
 * {@code toString()} and of the future protobuf bytes).
 *
 * <p><b>Pinned to {@code SHA256withECDSA} over an EC P-256 key</b> (Codex 019eb9fb — narrow over alg-agility):
 * the permit is a high-privilege authorization artifact, so the algorithm + curve are fixed at the boundary,
 * not negotiable per-permit. The constructor rejects any other algorithm or a non-P-256 key.
 *
 * <p><b>Fail-closed:</b> as the FINAL permit-issuance boundary (independent of the broker), the signer REFUSES
 * (returns {@link Optional#empty()}) to sign anything not provably a complete, consistent, PILOT permit: any
 * blank security field, a non-positive validity window, an {@code alg}/{@code kid} mismatch, a NON-pilot
 * capability, or a capability↔commandHash inconsistency ({@code CONSTRAINED_PTY} MUST carry a command hash;
 * {@code VIEW_ONLY} MUST NOT). A broker bug or a future caller cannot get a too-broad permit signed. A crypto
 * error is also empty (never a half-signed permit).
 */
public final class RemoteBridgePermitSigner {

    /** Pinned permit signature algorithm (EC P-256). */
    public static final String PERMIT_ALG = "SHA256withECDSA";
    /** The current permit schema version the signer will sign. */
    public static final int PERMIT_VERSION = 1;

    /** The exact secp256r1 (NIST P-256) domain parameters — the pin compares against these, not just field size. */
    private static final ECParameterSpec SECP256R1 = secp256r1();

    private static ECParameterSpec secp256r1() {
        try {
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec("secp256r1"));
            return ap.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("secp256r1 parameters unavailable", e);
        }
    }

    private final PrivateKey signingKey;
    private final String kid;
    private final String alg;

    public RemoteBridgePermitSigner(PrivateKey signingKey, String kid, String alg) {
        if (signingKey == null) {
            throw new IllegalArgumentException("signingKey must not be null");
        }
        if (kid == null || kid.isBlank()) {
            throw new IllegalArgumentException("kid must not be blank");
        }
        if (!PERMIT_ALG.equals(alg)) {
            throw new IllegalArgumentException("permit signer is pinned to " + PERMIT_ALG);
        }
        if (!isP256(signingKey)) {
            throw new IllegalArgumentException("permit signing key must be EC P-256");
        }
        this.signingKey = signingKey;
        this.kid = kid;
        this.alg = alg;
    }

    /** True iff {@code key} is an EC key on EXACTLY the secp256r1 (NIST P-256) curve — full domain-parameter
     *  compare (curve a/b/field + order + cofactor + generator), so a different 256-bit curve (secp256k1) is
     *  rejected, not just a different field size (Codex 019eb9fb). */
    static boolean isP256(Key key) {
        if (!(key instanceof ECKey ecKey) || ecKey.getParams() == null) {
            return false;
        }
        ECParameterSpec p = ecKey.getParams();
        return p.getCurve().equals(SECP256R1.getCurve())
                && p.getOrder().equals(SECP256R1.getOrder())
                && p.getCofactor() == SECP256R1.getCofactor()
                && p.getGenerator().equals(SECP256R1.getGenerator());
    }

    public String kid() {
        return kid;
    }

    public String alg() {
        return alg;
    }

    /** Sign the permit, or {@link Optional#empty()} if it is incomplete / inconsistent / un-signable. */
    public Optional<OperationPermit> sign(OperationPermit unsigned) {
        if (!isSignable(unsigned)) {
            return Optional.empty(); // refuse to sign an incomplete/inconsistent permit
        }
        try {
            Signature signature = Signature.getInstance(alg);
            signature.initSign(signingKey);
            signature.update(unsigned.canonicalPayload());
            String signatureB64 = Base64.getEncoder().encodeToString(signature.sign());
            return Optional.of(unsigned.withSignature(signatureB64));
        } catch (GeneralSecurityException e) {
            return Optional.empty(); // never emit a half-signed permit
        }
    }

    private boolean isSignable(OperationPermit p) {
        if (p == null
                || !alg.equals(p.alg()) || !kid.equals(p.kid())
                || p.permitVersion() != PERMIT_VERSION || p.seq() < 0
                || !notBlank(p.policyVersion()) || !notBlank(p.decisionId()) || !notBlank(p.sessionId())
                || !notBlank(p.operationId()) || !notBlank(p.deviceId()) || !notBlank(p.operatorSubject())
                || p.commandHash() == null
                || p.issuedAtEpochMillis() < 0
                || p.expiresAtEpochMillis() <= p.issuedAtEpochMillis()
                || !WireContract.isPilotCapability(p.capability())) { // a NON-pilot capability is never signed
            return false;
        }
        // capability <-> commandHash consistency (Codex): PTY must carry a command; VIEW_ONLY must not
        return switch (p.capability()) {
            case CONSTRAINED_PTY -> !p.commandHash().isBlank();
            case VIEW_ONLY -> p.commandHash().isEmpty();
            default -> false; // unreachable (isPilotCapability guard) but default-deny
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
