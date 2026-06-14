package com.example.endpointadmin.tpmattest;

/**
 * Faz 22.3B (ADR-0039) gate-4 — a verifier deny carrying its {@link TpmDenyCode}.
 *
 * <p>The code is <b>audit-only</b>: the gate-4d controller maps every
 * {@code TpmAttestException} to one uniform {@code 403} + fixed body and records
 * the code (and detail) only in the append-only attestation audit log — never on
 * the wire (design §9, no behavioral/enumeration oracle).
 */
public final class TpmAttestException extends RuntimeException {

    private final transient TpmDenyCode denyCode;

    public TpmAttestException(TpmDenyCode denyCode, String detail) {
        super(denyCode + ": " + detail);
        this.denyCode = denyCode;
    }

    public TpmAttestException(TpmDenyCode denyCode, String detail, Throwable cause) {
        super(denyCode + ": " + detail, cause);
        this.denyCode = denyCode;
    }

    public TpmDenyCode denyCode() {
        return denyCode;
    }
}
