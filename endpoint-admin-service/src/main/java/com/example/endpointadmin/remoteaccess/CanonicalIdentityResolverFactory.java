package com.example.endpointadmin.remoteaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — selects the {@link CanonicalIdentityResolver} with a blocking matrix at
 * construction (= bean creation = STARTUP fail-fast), mirroring the operator-authenticator / step-up-verifier
 * factory pattern:
 * <ul>
 *   <li><b>IN_MEMORY</b> — the {@link InMemoryCanonicalIdentityResolver}, a PLACEHOLDER static mapping (NOT a
 *       real IdP/directory). FORBIDDEN in a production-like profile → fail-fast; a real runtime must resolve
 *       aliases/proxies/service-accounts against the live identity provider.</li>
 *   <li><b>IDP_BACKED</b> — the real resolver (a directory/IdP that resolves alias/proxy/service-account
 *       ownership to a canonical subject). NOT YET IMPLEMENTED (the live identity-plane slice) → rejected here
 *       rather than silently built, so no half-wired canonicalization exists (which would let an unmapped
 *       alias defeat dual-control).</li>
 * </ul>
 */
public final class CanonicalIdentityResolverFactory {

    private static final Logger log = LoggerFactory.getLogger(CanonicalIdentityResolverFactory.class);

    public enum ResolverType { IN_MEMORY, IDP_BACKED }

    private CanonicalIdentityResolverFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access canonical-identity resolver config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * @param inMemoryMapping       the reference principal→canonical mapping (used only for IN_MEMORY)
     * @param productionLikeProfile when true, the placeholder IN_MEMORY resolver is REFUSED (a real runtime
     *                              must resolve against the live IdP)
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static CanonicalIdentityResolver create(ResolverType type, Map<String, String> inMemoryMapping,
                                                   boolean productionLikeProfile) {
        ResolverType t = type == null ? ResolverType.IN_MEMORY : type; // placeholder default (non-prod)
        switch (t) {
            case IN_MEMORY -> {
                if (productionLikeProfile) {
                    throw reject("canonical-identity resolver IN_MEMORY is a PLACEHOLDER static mapping (not a "
                            + "real IdP) and is forbidden in a production-like profile — configure an IdP-backed "
                            + "resolver so aliases/proxies/service-accounts resolve to a canonical subject");
                }
                return new InMemoryCanonicalIdentityResolver(inMemoryMapping == null ? Map.of() : inMemoryMapping);
            }
            case IDP_BACKED -> throw reject("canonical-identity resolver IDP_BACKED is not yet implemented (the "
                    + "live identity-plane slice) — refusing rather than half-wiring dual-control canonicalization");
            default -> throw reject("unreachable canonical-identity resolver type " + t);
        }
    }
}
