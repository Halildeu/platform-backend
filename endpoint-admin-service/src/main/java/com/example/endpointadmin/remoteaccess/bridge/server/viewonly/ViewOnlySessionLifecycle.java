package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import java.util.Objects;

/**
 * Faz 22.6 #1580 (Codex 019f0e78 RED absorb) — the single VIEW_ONLY session-lifecycle seam shared by every
 * component that authorizes or terminates a session, so the fanout authorization and the live viewers are
 * cleaned up TOGETHER on EVERY terminal path — operator-driven (kill / deny / close) AND agent-driven
 * (consent-denied / local-abort / active-indicator-lost, handled by {@code BrokerControlPlane}).
 *
 * <p>Why this exists: the first cut revoked the authorization only on the operator-driven terminals, so an
 * endpoint user's local abort (or a lost active-session indicator) terminated the session while a stale
 * VIEW_ONLY authorization stayed live until the permit expiry — screen frames could still be fanned out after
 * the user pulled consent. That is not fail-closed. {@link #terminate} makes termination atomic across BOTH
 * registries and is wired into every terminal path.
 */
public final class ViewOnlySessionLifecycle {

    private final ViewOnlyStreamAuthorizationRegistry authorization;
    private final ViewOnlyViewerRegistry viewers;

    public ViewOnlySessionLifecycle(ViewOnlyStreamAuthorizationRegistry authorization,
                                    ViewOnlyViewerRegistry viewers) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.viewers = Objects.requireNonNull(viewers, "viewers");
    }

    /** Record a VIEW_ONLY stream authorization (on a delivered VIEW_ONLY permit). */
    public void authorizeStream(ViewOnlyStreamAuthorizationRegistry.Authorization authorization) {
        this.authorization.authorize(authorization);
    }

    /**
     * Terminate a session's VIEW_ONLY surface: revoke every stream authorization (so nothing further is fanned
     * out — fail-closed) AND detach every viewer (so no held frame lingers). Idempotent; safe on a session that
     * never had a VIEW_ONLY permit.
     */
    public void terminate(String sessionId) {
        if (sessionId == null) {
            return;
        }
        authorization.revokeSession(sessionId);
        viewers.closeSession(sessionId);
    }
}
