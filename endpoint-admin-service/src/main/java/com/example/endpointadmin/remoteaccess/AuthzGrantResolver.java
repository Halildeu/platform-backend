package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — the seam that produces the dual-control authorization GRANTS for a
 * session-request approval: does {@code principalId} hold the {@code can_request} / {@code can_approve} relation
 * on a given {@code remote_session} resource (ADR-0033 §4 + ADR-0034 §11 must-land #10)?
 *
 * <p>This exists so {@link RemoteSessionApprovalFlow} resolves the grant booleans SERVER-SIDE rather than
 * accepting them from a caller/client/body — closing the obvious dual-control bypass where a client simply
 * asserts {@code canApprove=true}. The grant truth is owned by the authz plane (OpenFGA), never by the request.
 *
 * <p><b>Fail-closed contract:</b> an unknown principal/resource, or a null/blank argument, resolves to
 * {@code false} (no grant). The grant lookup is on the RAW presented principal — mapping an alias/proxy/
 * service-account to a canonical subject before the grant check is the LIVE resolver's (OpenFGA tuple model's)
 * responsibility, not this seam's; a raw lookup that misses a canonical-only grant fails CLOSED (deny), which is
 * safe. The in-memory reference ({@link InMemoryAuthzGrantResolver}) is a placeholder; the live OpenFGA-backed
 * resolver is the owner-gated slice ({@link AuthzGrantResolverFactory} forbids the placeholder in production).
 */
public interface AuthzGrantResolver {

    /**
     * @return true iff {@code principalId} holds {@code can_request} on the {@code remote_session} resource
     *         {@code sessionResourceId}; false for any unknown/null/blank argument (fail-closed).
     */
    boolean hasCanRequest(String principalId, String sessionResourceId);

    /**
     * @return true iff {@code principalId} holds {@code can_approve} on the {@code remote_session} resource
     *         {@code sessionResourceId}; false for any unknown/null/blank argument (fail-closed).
     */
    boolean hasCanApprove(String principalId, String sessionResourceId);
}
