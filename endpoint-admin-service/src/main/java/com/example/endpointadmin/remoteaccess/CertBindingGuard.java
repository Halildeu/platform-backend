package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 B1.1c — the single cert-binding decision (Codex 019eb54b B1.1c check-list). Pure + total:
 * every (bound, presented, policy) input maps to an explicit {@link Decision}, never a throw. Used by
 * the connect-time {@link CertBoundConsumeGate} and the mid-session heartbeat
 * ({@link RemoteSessionHeartbeat}) so both enforce the SAME truth table — no drift.
 *
 * <p><b>Fail-closed core:</b> a BOUND token proceeds only with the exact matching presented thumbprint
 * (constant-time, hex-normalized via {@link CertThumbprint#matches}); a missing/blank presented cert on
 * a bound token is a rejection regardless of any flag. A legacy-UNBOUND token proceeds only under the
 * explicit {@link Policy#ALLOW_LEGACY_UNBOUND} migration flag — silence means reject (check-list #3:
 * no ACTIVE for a legacy-unbound token without a deliberate policy decision).
 */
public final class CertBindingGuard {

    /**
     * The legacy-unbound feature flag (B1.1 migration). Bound from
     * {@code endpoint-admin.remote-access.cert-binding.legacy-unbound-allowed} (default {@code false}).
     * <ul>
     *   <li>{@link #REQUIRE_BOUND} — production target: every token must be cert-bound; a legacy-unbound
     *       token can neither connect nor stay ACTIVE (fail-closed default).</li>
     *   <li>{@link #ALLOW_LEGACY_UNBOUND} — migration window only: an unbound token may proceed, every
     *       such consume is metered ({@link RemoteAccessMetrics#LEGACY_UNBOUND_ISSUANCE}) so the window
     *       is observable and closable. A BOUND token's match requirement is NOT relaxed by this flag.</li>
     * </ul>
     */
    public enum Policy { REQUIRE_BOUND, ALLOW_LEGACY_UNBOUND }

    /** The explicit, auditable outcome of one binding evaluation. */
    public enum Decision {
        /** Token is bound and the presented thumbprint matches exactly (constant-time). */
        BOUND_MATCH(true),
        /** Token is legacy-unbound and the policy explicitly allows the migration window. */
        UNBOUND_ALLOWED(true),
        /** Token is legacy-unbound and the policy is {@link Policy#REQUIRE_BOUND} → reject. */
        UNBOUND_REJECTED(false),
        /** Token is bound but no presented thumbprint was supplied → reject (flag-independent). */
        PRESENTED_MISSING(false),
        /** Token is bound and the presented thumbprint differs → reject (possible token theft). */
        MISMATCH(false);

        private final boolean satisfied;

        Decision(boolean satisfied) {
            this.satisfied = satisfied;
        }

        /** Whether this decision satisfies the {@code certBound} precondition. */
        public boolean satisfied() {
            return satisfied;
        }
    }

    /**
     * Decide whether a presented client-cert thumbprint satisfies a token's binding under the policy.
     * A {@code null} policy is treated as {@link Policy#REQUIRE_BOUND} (fail-closed default).
     *
     * @param boundThumbprint     the thumbprint pinned at consume ({@code null}/blank = legacy-unbound)
     * @param presentedThumbprint the live TLS-layer client-cert thumbprint ({@code null}/blank = none)
     */
    public static Decision decide(String boundThumbprint, String presentedThumbprint, Policy policy) {
        if (CertThumbprint.isPresent(boundThumbprint)) {
            if (!CertThumbprint.isPresent(presentedThumbprint)) {
                return Decision.PRESENTED_MISSING;
            }
            return CertThumbprint.matches(boundThumbprint, presentedThumbprint)
                    ? Decision.BOUND_MATCH
                    : Decision.MISMATCH;
        }
        return policy == Policy.ALLOW_LEGACY_UNBOUND
                ? Decision.UNBOUND_ALLOWED
                : Decision.UNBOUND_REJECTED;
    }

    private CertBindingGuard() {
    }
}
