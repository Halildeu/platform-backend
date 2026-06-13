package com.example.endpointadmin.remoteaccess;

import java.util.Optional;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — resolves a presented principal id (which may be an alias, a proxy
 * identity, or a service-account) to a STABLE canonical subject, so the dual-control invariant
 * ({@link RemoteSessionAuthz#approverDistinctFromRequester}) can be enforced on the canonical subject, not the
 * raw id. {@link RemoteSessionAuthz} explicitly does NOT defend against one human acting under two distinct
 * ids; this seam closes that gap (red-team must-land #10: IAM identity canonicalization for dual-control,
 * "alias/proxy/service-account resolved before approver≠requester").
 *
 * <p><b>Fail-closed contract:</b> a principal that cannot be resolved to a canonical subject yields
 * {@link Optional#empty()} — a caller MUST treat that as a denial (an unresolvable approver/requester can
 * never be proven distinct). Total: never throws, never returns null. A blank/null principal is unresolvable.
 *
 * <p><b>Seam, producer deferred:</b> the reference {@link InMemoryCanonicalIdentityResolver} is a configured
 * static mapping for tests/disabled-by-default; the live producer — a real IdP/directory that resolves
 * aliases, proxy delegations, and service-account ownership to a canonical subject — is owner-gated and wired
 * later (the {@link CanonicalIdentityResolverFactory} forbids the reference in a production-like profile).
 */
public interface CanonicalIdentityResolver {

    /**
     * The stable canonical subject for a presented principal, or empty when it cannot be resolved (fail-closed
     * — the caller denies). The returned subject is trimmed + non-blank when present.
     */
    Optional<String> canonicalSubject(String principalId);
}
