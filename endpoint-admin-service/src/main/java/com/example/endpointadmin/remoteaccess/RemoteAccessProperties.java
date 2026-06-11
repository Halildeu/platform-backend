package com.example.endpointadmin.remoteaccess;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Faz 22.6 remote-access feature flag (ADR-0034 #1388 / D10). <b>Disabled by default.</b>
 *
 * <p>Even with {@code enabled=true}, no live session may open until the §11/D10 acceptance gate
 * (broker negative-tests + recording fail-closed + D29-EA) is met. This skeleton ships the
 * policy/state-machine/token/audit contracts only — there is no tunnel runtime to enable yet.
 *
 * <p>Bound from {@code endpoint-admin.remote-access.*} (env {@code ENDPOINT_ADMIN_REMOTE_ACCESS_ENABLED}).
 */
@ConfigurationProperties(prefix = "endpoint-admin.remote-access")
public class RemoteAccessProperties {

    /** Master switch. Default false — runtime stays off until #1388 live-acceptance (D10). */
    private boolean enabled = false;

    /** Cert-binding enforcement flags (B1.1c). */
    private final CertBinding certBinding = new CertBinding();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public CertBinding getCertBinding() {
        return certBinding;
    }

    /**
     * B1.1c legacy-unbound migration flag, bound from
     * {@code endpoint-admin.remote-access.cert-binding.legacy-unbound-allowed} (env
     * {@code ENDPOINT_ADMIN_REMOTE_ACCESS_CERTBINDING_LEGACYUNBOUNDALLOWED}). <b>Default {@code false}
     * (fail-closed):</b> every token must be cert-bound; an unbound token can neither connect
     * ({@link CertBoundConsumeGate}) nor go/stay ACTIVE ({@code certBound} precondition).
     *
     * <p><b>Migration path (documented per Codex 019eb54b B1.1):</b> flip to {@code true} ONLY for the
     * window in which pre-B1.1 issued tokens are still in flight; watch
     * {@link RemoteAccessMetrics#LEGACY_UNBOUND_ISSUANCE} until it flatlines at zero, then flip back to
     * {@code false}. The flag never relaxes a BOUND token's exact-match requirement, and a mid-session
     * flip to {@code false} hard-kills any still-running unbound session on its next heartbeat.
     */
    public static class CertBinding {

        private boolean legacyUnboundAllowed = false;

        public boolean isLegacyUnboundAllowed() {
            return legacyUnboundAllowed;
        }

        public void setLegacyUnboundAllowed(boolean legacyUnboundAllowed) {
            this.legacyUnboundAllowed = legacyUnboundAllowed;
        }

        /** The {@link CertBindingGuard.Policy} this flag selects. */
        public CertBindingGuard.Policy policy() {
            return legacyUnboundAllowed
                    ? CertBindingGuard.Policy.ALLOW_LEGACY_UNBOUND
                    : CertBindingGuard.Policy.REQUIRE_BOUND;
        }
    }
}
