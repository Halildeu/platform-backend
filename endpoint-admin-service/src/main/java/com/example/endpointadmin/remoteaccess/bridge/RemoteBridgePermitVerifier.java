package com.example.endpointadmin.remoteaccess.bridge;

import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;

/**
 * Faz 22.6 T-1b — the agent-side {@link OperationPermit} verifier (the reference implementation; the real agent
 * verifier lands in T-3/Go). It holds ONLY the broker's PUBLIC key — it can verify a permit but never mint one
 * (the broker-private / agent-public model, Codex 019eb9fb). A permit is accepted iff its {@code kid} matches,
 * its {@code alg} is allowlisted, it is fresh at {@code now}, and the signature verifies over
 * {@link OperationPermit#canonicalPayload()}. Fail-closed: any mismatch / tamper / expiry / crypto error → false.
 */
public final class RemoteBridgePermitVerifier {

    private final PublicKey verifyKey;
    private final String expectedKid;

    public RemoteBridgePermitVerifier(PublicKey verifyKey, String expectedKid) {
        if (!RemoteBridgePermitSigner.isP256(verifyKey)) {
            throw new IllegalArgumentException("permit verify key must be EC P-256");
        }
        if (expectedKid == null || expectedKid.isBlank()) {
            throw new IllegalArgumentException("expectedKid must not be blank");
        }
        this.verifyKey = verifyKey;
        this.expectedKid = expectedKid;
    }

    /** True iff the permit is from the expected key + pinned alg, fresh at {@code now}, and its signature verifies. */
    public boolean verify(OperationPermit permit, long nowEpochMillis) {
        if (permit == null
                || !expectedKid.equals(permit.kid())
                || !RemoteBridgePermitSigner.PERMIT_ALG.equals(permit.alg()) // pinned alg (config-drift guard)
                || permit.signatureB64() == null || permit.signatureB64().isBlank()
                || !permit.isFresh(nowEpochMillis)) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(permit.alg());
            signature.initVerify(verifyKey);
            signature.update(permit.canonicalPayload());
            return signature.verify(Base64.getDecoder().decode(permit.signatureB64()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false; // bad alg / key / signature / base64 → fail-closed
        }
    }
}
