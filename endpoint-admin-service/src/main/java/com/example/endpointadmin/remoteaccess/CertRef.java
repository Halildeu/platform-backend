package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 B1.2 — a reference to the client certificate presented over the transport, the input to
 * {@link CertTrustEvaluator} (Codex 019eb694 Q3 absorb: a small value object, not a bare thumbprint String,
 * so B1.4 can carry the real chain identity — serial + issuer for CRL/OCSP path-build + audit — without
 * changing the evaluator signature). Today only {@link #thumbprint} is populated (the same SHA-256 hex used
 * by the B1.1 binding); {@link #serialNumber} / {@link #issuerDn} are reserved for the B1.4 real-PKI seam.
 *
 * <p>Pure data — no validation here; trust/revocation/expiry is the evaluator's job.
 */
public record CertRef(String thumbprint, String thumbprintAlg, String serialNumber, String issuerDn) {

    /** A cert known only by its B1.1 binding thumbprint (SHA-256). B1.4 enriches with serial/issuer. */
    public static CertRef ofThumbprint(String thumbprint) {
        return new CertRef(thumbprint, "SHA-256", null, null);
    }

    /** Whether a cert was actually presented (a non-blank thumbprint). */
    public boolean isPresent() {
        return CertThumbprint.isPresent(thumbprint);
    }
}
