package com.example.commonauth.identity;

import java.util.Optional;

/**
 * Port to the canonical user-identity authority (board #2532, umbrella #2530).
 *
 * <p><b>Why this exists.</b> Three different subject conventions were live at once:
 * {@code user:<numeric DB id>} (TupleSyncService, endpoint-admin tuples), {@code user:<KC sub>}
 * (canonical /access/scope grants) and {@code user:<email>} (local fixtures). endpoint-admin's
 * interceptor resolved the OpenFGA subject as "the {@code userId} claim, else {@code jwt.getSubject()}"
 * — so a normal browser token (which carries {@code sub}+{@code email} but no {@code userId} claim)
 * asked OpenFGA about a Keycloak UUID while every tuple was written for the numeric id. The check
 * could only answer "no" → an authorized admin got 403. permission-service already resolved
 * email→numeric properly; endpoint-admin did not. That divergence is the bug
 * ({@code AuthorizationControllerV1} documents the same class of defect as OI-03).
 *
 * <p><b>Contract.</b> common-auth owns the <em>algorithm</em> (which claims to trust, how to bind
 * them, when to fail closed); it deliberately does NOT know which database or URL holds the row.
 * The concrete adapter lives in the consuming service and talks to <em>user-service</em>, which is
 * the canonical owner of the identity row. Implementations must not read another service's schema.
 *
 * <p><b>Fail-closed.</b> An implementation returns {@link Optional#empty()} only for "no such
 * canonical user". Any inability to answer (timeout, 5xx, misconfiguration) must throw
 * {@link UserIdentityDirectoryUnavailableException} rather than returning empty — an empty result
 * means "this principal has no account", which callers translate to a deny, whereas an outage must
 * never be silently converted into a deny/allow decision based on unverified claims.
 */
public interface UserIdentityDirectory {

    /**
     * Resolve the canonical identity for an already-validated token.
     *
     * @param issuer  token issuer (already validated by the resource server)
     * @param subject Keycloak {@code sub}; preferred binding key
     * @param email   canonical email claim; controlled fallback when the row predates subject binding
     * @return the canonical identity, or empty when no such user exists
     * @throws UserIdentityDirectoryUnavailableException when the authority cannot be consulted
     */
    Optional<ResolvedUserIdentity> resolve(String issuer, String subject, String email);
}
