package com.example.endpointadmin.tpmattest;

/**
 * Faz 22.3B (ADR-0039) gate-4 — result of the attestation verifier (design §4).
 *
 * <p>On success carries the derived device identity ({@code ek_pub_sha256}, which
 * becomes the issued cert's {@code SAN URI = tpm:<ek_pub_sha256>}). On failure
 * carries the {@link TpmDenyCode} for the <b>audit log only</b> — the caller maps
 * any failure to a single uniform {@code 403} (design §9), never returning the
 * code.
 */
public record TpmAttestVerdict(boolean ok, TpmDenyCode denyCode, String ekPubSha256) {

    public static TpmAttestVerdict ok(String ekPubSha256) {
        return new TpmAttestVerdict(true, null, ekPubSha256);
    }

    public static TpmAttestVerdict deny(TpmDenyCode code) {
        return new TpmAttestVerdict(false, code, null);
    }

    /** The opaque SAN URI the backend injects into the Vault PKI issue call. */
    public String sanUri() {
        return ok ? "tpm:" + ekPubSha256 : null;
    }
}
