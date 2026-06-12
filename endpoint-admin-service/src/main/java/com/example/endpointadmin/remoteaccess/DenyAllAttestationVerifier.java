package com.example.endpointadmin.remoteaccess;

import java.time.Instant;

/**
 * Faz 22.6 T-4a-ii slice-2 (Codex 019ebc7e) — an explicit deny-all {@link AttestationVerifier}: every
 * verification is {@link AttestationDecision#MISSING} (not verified). This is the fail-closed coercion for an
 * UNCONFIGURED attestation policy.
 *
 * <p>Why a concrete adapter and not {@code null}: the revocation runtime coerces a {@code null} verifier to
 * deny-all inside its heartbeat, but the broker's {@code PeerTrustLedger} constructor REQUIRES a non-null
 * verifier (it never wants a silent skip). So the remote-bridge wiring wraps an unconfigured (null) verifier
 * in THIS adapter — an enabled bridge with no attestation policy then refuses every attested session, rather
 * than NPE-ing or skipping the check.
 */
public final class DenyAllAttestationVerifier implements AttestationVerifier {

    public static final DenyAllAttestationVerifier INSTANCE = new DenyAllAttestationVerifier();

    private DenyAllAttestationVerifier() {
    }

    @Override
    public AttestationDecision verify(AttestationEvidence evidence, Instant now) {
        return AttestationDecision.MISSING; // no policy configured → nothing is ever verified
    }
}
