package com.example.gpcore.domain;

import java.util.Objects;
import java.util.Set;

/**
 * Authoritative subject-side ABAC attributes, resolved per-request via
 * {@code SubjectAttributePort}. Absence (port returns empty / throws) is treated
 * as DENY by {@link com.example.gpcore.authz.AuthorizationDecisionService} — a
 * decision is never made with missing subject context (Codex 019f1913 #3).
 *
 * @param clearances           clearance tokens the subject holds (e.g.
 *                             {@code "clearance:special_category"}); used by the
 *                             deny-overrides ABAC layer for restricted/special
 *                             classifications.
 * @param subjectPolicyVersion opaque version stamp for the subject's attribute
 *                             set; participates in the decision cache key so a
 *                             clearance change/revocation invalidates cached
 *                             positives.
 */
public record SubjectAttributes(Set<String> clearances, String subjectPolicyVersion) {

    public SubjectAttributes {
        Objects.requireNonNull(clearances, "clearances");
        Objects.requireNonNull(subjectPolicyVersion, "subjectPolicyVersion");
        clearances = Set.copyOf(clearances);
    }

    public boolean hasClearance(String token) {
        return clearances.contains(token);
    }
}
