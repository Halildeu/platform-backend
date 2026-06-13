package com.example.endpointadmin.remoteaccess;

import java.util.Map;
import java.util.Optional;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — the in-memory REFERENCE {@link CanonicalIdentityResolver}: a configured
 * static principal→canonical-subject mapping for tests + the disabled-by-default skeleton. A PLACEHOLDER trust
 * basis, NOT a real IdP/directory; the {@link CanonicalIdentityResolverFactory} forbids it in a production-like
 * profile.
 *
 * <p><b>Fail-closed:</b> a principal NOT in the mapping resolves to empty (an unknown principal is unresolvable,
 * never silently passed through as its own canonical subject — that would let an unmapped alias defeat the
 * dual-control check). A blank/null principal is empty. The mapping is normalized to trimmed keys; lookup
 * trims the presented id. Values are validated non-blank at construction.
 */
public final class InMemoryCanonicalIdentityResolver implements CanonicalIdentityResolver {

    private final Map<String, String> principalToCanonical;

    /**
     * @param principalToCanonical the reference mapping — every alias/proxy/service-account id that may appear
     *                             as a requester or approver mapped to its stable canonical subject (a
     *                             canonical subject maps to itself). Keys + values are trimmed; a blank key or
     *                             value is rejected (a blank mapping would be a fail-open hole).
     */
    public InMemoryCanonicalIdentityResolver(Map<String, String> principalToCanonical) {
        if (principalToCanonical == null) {
            throw new IllegalArgumentException("principalToCanonical must not be null");
        }
        Map<String, String> normalized = new java.util.HashMap<>();
        principalToCanonical.forEach((principal, canonical) -> {
            if (principal == null || principal.isBlank() || canonical == null || canonical.isBlank()) {
                throw new IllegalArgumentException("blank principal or canonical subject is not allowed");
            }
            normalized.put(principal.strip(), canonical.strip());
        });
        this.principalToCanonical = Map.copyOf(normalized);
    }

    @Override
    public Optional<String> canonicalSubject(String principalId) {
        if (principalId == null || principalId.isBlank()) {
            return Optional.empty();
        }
        // exact (trimmed) lookup; an unmapped principal is unresolvable (fail-closed), never pass-through.
        // case-folding is intentionally NOT done — principal-id case-sensitivity is provider-defined, and
        // folding could wrongly equate two genuinely-distinct ids (mirrors RemoteSessionAuthz's decision).
        return Optional.ofNullable(principalToCanonical.get(principalId.strip()));
    }
}
