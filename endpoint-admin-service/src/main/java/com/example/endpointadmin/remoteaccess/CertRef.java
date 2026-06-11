package com.example.endpointadmin.remoteaccess;

import java.util.List;

/**
 * Faz 22.6 B1.2 — a reference to the client certificate presented over the transport, the input to
 * {@link CertTrustEvaluator} (Codex 019eb694 Q3 absorb: a small value object, not a bare thumbprint String,
 * so B1.4 can carry the real chain identity without changing the evaluator signature). {@link #thumbprint}
 * is the SHA-256 hex used by the B1.1 binding; {@link #serialNumber} / {@link #issuerDn} are the B1.4a-0
 * identity pin; {@link #encodedChain} (B1.4a-2) is the leaf-first DER/PEM cert chain the B1.4a PKIX
 * {@code CertPathTrustEvaluator} validates.
 *
 * <p>Pure data — no validation here; trust/revocation/expiry is the evaluator's job. The chain bytes are
 * defensively copied in and out (a security value object must not share mutable array state).
 */
public record CertRef(String thumbprint, String thumbprintAlg, String serialNumber, String issuerDn,
                      List<byte[]> encodedChain) {

    /** Defensive copy + {@code null}→empty so the stored chain can't be mutated through the source list. */
    public CertRef {
        encodedChain = encodedChain == null
                ? List.of()
                : encodedChain.stream().map(b -> b == null ? new byte[0] : b.clone()).toList();
    }

    /**
     * Back-compat constructor (no presented chain) so every B1.1 / B1.2 / B1.4a-0 call-site is unchanged.
     * The chain-carrying canonical constructor is used only by the cert-sampling live path (B1.4a-3).
     */
    public CertRef(String thumbprint, String thumbprintAlg, String serialNumber, String issuerDn) {
        this(thumbprint, thumbprintAlg, serialNumber, issuerDn, List.of());
    }

    /** A cert known only by its B1.1 binding thumbprint (SHA-256). */
    public static CertRef ofThumbprint(String thumbprint) {
        return new CertRef(thumbprint, "SHA-256", null, null);
    }

    /** The presented chain, defensively copied out (mutating the result cannot corrupt this CertRef). */
    @Override
    public List<byte[]> encodedChain() {
        return encodedChain.stream().map(byte[]::clone).toList();
    }

    /** Whether a cert was actually presented (a non-blank thumbprint). */
    public boolean isPresent() {
        return CertThumbprint.isPresent(thumbprint);
    }
}
