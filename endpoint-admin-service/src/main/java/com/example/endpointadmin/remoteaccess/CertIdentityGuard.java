package com.example.endpointadmin.remoteaccess;

import java.util.Objects;

/**
 * Faz 22.6 B1.4a-0 — the cert IDENTITY decision: does the presented client cert match the EXPECTED
 * identity constraints (issuer DN + serial number), beyond the B1.1 thumbprint binding and the B1.2
 * trust/revocation check? Pure + total: every {@code (expected, presented)} input maps to an explicit
 * {@link Decision}, never a throw. Mirrors the {@link CertBindingGuard} truth-table style so the heartbeat
 * enforces one shared, driftless rule.
 *
 * <p>This closes the B1.2 "reserved field" debt: {@link CertRef#serialNumber()} / {@link CertRef#issuerDn()}
 * were carried but never consulted. Here the EXPECTED identity (the operator-pinned agent-CA issuer, and a
 * future bound-token serial) is enforced against the PRESENTED cert. It is folded into the
 * {@code certValid} precondition (precedence #7) — NOT a new precondition — and refined into a distinct
 * {@code CERT_IDENTITY_*} {@link RemoteSessionStateMachine.KillReason} for audit/IR.
 *
 * <p><b>Fail-closed:</b> when an expected field is set, a presented cert that is missing it or differs is a
 * rejection. When NO expected identity is configured ({@code expected == null}), identity is
 * {@link Decision#NOT_ENFORCED} (the thumbprint binding + trust still apply) — backward-compatible with the
 * B1.1/B1.2 thumbprint-only world.
 *
 * <p><b>Scope (B1.4a-0):</b> the issuer DN is compared as an EXACT, case-sensitive string. Real RFC 4514
 * DN canonicalisation (attribute-order / whitespace / OID normalisation) + a full X.509 chain path-build is
 * the B1.4a PKIX slice; an exact match here is deterministic, offline, and strictly TIGHTER than no check.
 * The serial constraint is wired + tested but has no expected source yet (a per-cert serial is pinned by the
 * bound token, B1.4a/store) — it is enforced the moment an expected serial is supplied.
 */
public final class CertIdentityGuard {

    /** The explicit, auditable outcome of one identity evaluation. */
    public enum Decision {
        /** No expected identity configured → identity is not constrained (binding + trust still apply). */
        NOT_ENFORCED(true),
        /** Every configured expected field matches the presented cert. */
        MATCH(true),
        /** An expected issuer DN is set but the presented cert carries none → reject (fail-closed). */
        ISSUER_MISSING(false),
        /** An expected issuer DN is set and the presented cert's issuer differs → reject (wrong CA). */
        ISSUER_MISMATCH(false),
        /** An expected serial is set and the presented cert's serial is missing/differs → reject. */
        SERIAL_MISMATCH(false);

        private final boolean satisfied;

        Decision(boolean satisfied) {
            this.satisfied = satisfied;
        }

        /** Whether this decision satisfies the identity half of the {@code certValid} precondition. */
        public boolean satisfied() {
            return satisfied;
        }
    }

    /**
     * Decide whether the {@code presented} cert satisfies the {@code expected} identity constraints.
     * A {@code null} {@code expected} (no pin configured) → {@link Decision#NOT_ENFORCED}. Each expected
     * field is enforced only when present, in precedence order issuer → serial; a present-but-blank
     * presented value for a set expected field is treated as missing (fail-closed).
     *
     * @param expected  the configured/pinned identity constraints (issuer DN and/or serial), or {@code null}
     * @param presented the live cert's identity (thumbprint + serial + issuer), never {@code null} on the
     *                  cert-sampling path
     */
    public static Decision decide(CertRef expected, CertRef presented) {
        if (expected == null) {
            return Decision.NOT_ENFORCED;
        }
        if (isPresent(expected.issuerDn())) {
            if (presented == null || !isPresent(presented.issuerDn())) {
                return Decision.ISSUER_MISSING;
            }
            if (!Objects.equals(expected.issuerDn(), presented.issuerDn())) {
                return Decision.ISSUER_MISMATCH;
            }
        }
        if (isPresent(expected.serialNumber())) {
            String presentedSerial = presented == null ? null : presented.serialNumber();
            if (!isPresent(presentedSerial) || !Objects.equals(expected.serialNumber(), presentedSerial)) {
                return Decision.SERIAL_MISMATCH;
            }
        }
        return Decision.MATCH;
    }

    private static boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }

    private CertIdentityGuard() {
    }
}
