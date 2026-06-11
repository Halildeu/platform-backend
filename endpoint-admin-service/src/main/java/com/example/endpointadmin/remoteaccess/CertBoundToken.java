package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 B1.1 — the {@code jti}→certificate binding (Codex 019eb54b B1 plan, RFC 8705 mTLS-bound
 * token). A session token is bound to the SHA-256 hex thumbprint of the client certificate it was issued
 * for; at every connect + heartbeat the <b>presented</b> cert's thumbprint must equal the <b>bound</b>
 * one, else the session is fail-closed (B1.1c enforces this in the heartbeat / state machine). This is a
 * pure value object: the store pins {@link #boundThumbprint} atomically at {@code consume} (B1.1b) and
 * the runtime presents the live thumbprint from the TLS layer (B1.4 transport seam).
 *
 * <p><b>Legacy-unbound:</b> a token issued before cert-binding (or under the legacy-allow feature flag)
 * has a {@code null}/blank {@link #boundThumbprint} → {@link #isBound()} is {@code false}. Whether an
 * unbound token may go ACTIVE is a deliberate feature-flag decision (B1.1c), NOT silently allowed here:
 * {@link #bindingMatches} on an unbound token is always {@code false} (fail-closed by default).
 */
public record CertBoundToken(String jti, String boundThumbprint) {

    /** Whether this token carries an actual cert binding (a non-blank bound thumbprint). */
    public boolean isBound() {
        return CertThumbprint.isPresent(boundThumbprint);
    }

    /**
     * Fail-closed binding check: {@code true} only if this token is bound AND the presented thumbprint
     * equals the bound one (constant-time). An unbound token, a null/blank presented thumbprint, or a
     * mismatch all return {@code false} — the caller (B1.1c) decides whether an unbound token is allowed
     * under the legacy flag, but a BOUND token can only proceed with the exact presented cert.
     */
    public boolean bindingMatches(String presentedThumbprint) {
        return isBound() && CertThumbprint.matches(boundThumbprint, presentedThumbprint);
    }
}
