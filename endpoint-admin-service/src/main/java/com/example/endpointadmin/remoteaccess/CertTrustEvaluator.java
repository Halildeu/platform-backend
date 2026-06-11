package com.example.endpointadmin.remoteaccess;

import java.time.Instant;

/**
 * Faz 22.6 B1.2 — evaluates whether a presented client certificate is TRUSTWORTHY (Codex 019eb694):
 * a valid chain to a trust anchor, not expired, and not revoked per CRL/OCSP. This is ORTHOGONAL to the
 * B1.1 cert-binding (which asks "is this the RIGHT cert for this token"); trust asks "is this cert OK at
 * all". The runtime maps {@code evaluate(...).isValid()} to the {@code certValid} precondition, so a
 * non-trustworthy cert makes {@link RemoteSessionStateMachine#reevaluateActive} kill the session.
 *
 * <p><b>Fail-closed (criterion):</b> ONLY {@link TrustDecision#ALLOW} is trustworthy. Every other value —
 * including {@link TrustDecision#UNKNOWN} (never seen) and {@link TrustDecision#STALE} (revocation status
 * too old) — means kill. There is deliberately NO grace window for STALE: a stale revocation check is
 * exactly the window an attacker with a just-revoked cert wants (Codex 019eb694 Q4).
 *
 * <p>The in-memory reference impl models the chain/CRL/OCSP responses; the real X.509 path-build + live
 * CRL distribution-point + OCSP responder is the B1.4 transport seam (same in-memory-vs-real split as the
 * {@link TokenLifecycleStore}).
 */
public interface CertTrustEvaluator {

    /** The trust verdict for a presented cert. Only {@link #ALLOW} permits the session to proceed. */
    enum TrustDecision {
        /** Valid chain, not expired, and a FRESH not-revoked CRL/OCSP answer. */
        ALLOW(true),
        /** CRL/OCSP says the cert is revoked. */
        REVOKED(false),
        /** The cert is outside its validity window. */
        EXPIRED(false),
        /** No valid chain to a configured trust anchor. */
        NOT_TRUSTED(false),
        /** Never seen / no revocation answer available — fail-closed (not implicitly trusted). */
        UNKNOWN(false),
        /** A cached answer older than the freshness window — fail-closed (no grace). */
        STALE(false);

        private final boolean valid;

        TrustDecision(boolean valid) {
            this.valid = valid;
        }

        /** Whether this verdict satisfies the {@code certValid} precondition (ALLOW only). */
        public boolean isValid() {
            return valid;
        }
    }

    /**
     * Evaluate the trust of the presented cert at {@code now}. MUST be fail-closed: a null/absent cert, an
     * unreachable revocation source, an unknown cert, or a stale cache all return a non-ALLOW verdict.
     */
    TrustDecision evaluate(CertRef cert, Instant now);
}
