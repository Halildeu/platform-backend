package com.example.endpointadmin.remoteaccess;

import java.util.Objects;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — the disabled-by-default approval-flow ORCHESTRATOR that composes the E10
 * primitives into the single audited entry-point for a dual-control session-request approval (ADR-0033 §4 +
 * ADR-0034 §11 must-land #10). It is the thing the (owner-gated) broker approval endpoint would call.
 *
 * <p>Its reason for existing is a security invariant the {@link RemoteSessionApprovalGate} alone cannot close:
 * the gate takes the {@code can_request} / {@code can_approve} grants as BOOLEANS, so "where do those booleans
 * come from?" is still open. This flow answers it — it resolves the grants SERVER-SIDE from the
 * {@link AuthzGrantResolver} seam and never accepts them from a caller/client/body. Its {@code decide} signature
 * carries NO grant booleans, so the grant-injection bypass (a client asserting {@code canApprove=true}) is
 * structurally impossible.
 *
 * <p><b>Fail-closed:</b> a blank resource short-circuits to "no grant" (defense-in-depth against a fail-open
 * resolver); a contract-violating (throwing) grant resolver is caught and treated as "no grant" → the gate then
 * returns {@code DENIED_MISSING_GRANT}. The grant lookup is on the RAW principal — alias→canonical mapping for
 * grants is the live OpenFGA resolver's job (a raw miss is a safe deny), while the gate still canonicalizes for
 * the approver≠requester + fatigue checks. The outcome is the gate's internal audit reason; the CALLER must not
 * leak the distinct reasons to a client as an oracle.
 */
public final class RemoteSessionApprovalFlow {

    private final AuthzGrantResolver grants;
    private final RemoteSessionApprovalGate gate;

    public RemoteSessionApprovalFlow(AuthzGrantResolver grants, RemoteSessionApprovalGate gate) {
        this.grants = Objects.requireNonNull(grants, "grants");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /**
     * Decide a dual-control approval for the {@code remote_session} resource {@code sessionResourceId}. The
     * grants are resolved here (server-side) — the signature deliberately accepts NO grant booleans. An ALLOWED
     * outcome has recorded the approver against the fatigue cap; any denial records nothing.
     */
    public RemoteSessionApprovalGate.Outcome decide(String requesterPrincipal, String approverPrincipal,
                                                    String sessionResourceId, long nowEpochMillis) {
        boolean requesterHasCanRequest;
        boolean approverHasCanApprove;
        if (sessionResourceId == null || sessionResourceId.isBlank()) {
            // no resource → no grant can apply; short-circuit rather than trust a resolver to blank-check
            requesterHasCanRequest = false;
            approverHasCanApprove = false;
        } else {
            try {
                requesterHasCanRequest = grants.hasCanRequest(requesterPrincipal, sessionResourceId);
                approverHasCanApprove = grants.hasCanApprove(approverPrincipal, sessionResourceId);
            } catch (RuntimeException grantFault) {
                // a contract-violating grant resolver is fail-closed → treat as no grant; the gate then DENIES
                requesterHasCanRequest = false;
                approverHasCanApprove = false;
            }
        }
        // the gate owns identity-canonicalization, approver≠requester, and the fatigue cap (checked LAST)
        return gate.decide(requesterPrincipal, approverPrincipal,
                requesterHasCanRequest, approverHasCanApprove, nowEpochMillis);
    }
}
