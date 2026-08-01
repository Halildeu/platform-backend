package com.example.permission.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ADR-0049 (platform-k8s-gitops) — which side of the sale boundary each catalog module
 * stands on (#885, gitops#3178).
 *
 * <p>Owner decision 2026-08-01: every product is separately sellable, and a standalone
 * install carries only the common mandatory core plus what was bought. That decision is a
 * classification over {@link PermissionCatalogService#modules()}, and a classification that
 * lives only in a document rots — so it lives here, and {@code ModuleSaleClassificationTest}
 * closes the loop in both directions: a catalog key without a classification fails the
 * build, and a classified key that left the catalog fails it too. Whoever adds a module
 * decides its sale boundary in the same commit, or the build says no.
 *
 * <p>{@code CORE} is deliberately small and deliberately non-negotiable: {@code AUDIT}
 * optional would mean installs without compliance evidence — for the ethics product that is
 * the difference between defensible and not. {@code IMPERSONATION_AUDIT} exists to protect
 * the audit boundary itself, so it goes wherever {@code AUDIT} goes. {@code THEME} in core
 * is the "looks like someone else's product" lesson made permanent.
 */
public final class ModuleSaleClassification {

    /** Present in every install; never priced separately. */
    public static final Set<String> CORE = Set.of(
            "USER_MANAGEMENT",
            "ACCESS",
            "AUDIT",
            "IMPERSONATION_AUDIT",
            "THEME");

    /**
     * Sellable module → SKU. Two modules sharing a SKU ship together as one purchase —
     * ATS deliberately spans {@code ATS} + {@code INTERVIEW_EVIDENCE} while keeping them
     * separate <em>authorization</em> boundaries (a recruiter runs hiring without touching
     * sensitive interview evidence; both stay explicit-grant-only in
     * {@link PermissionModulePolicy}).
     */
    public static final Map<String, String> SELLABLE = Map.ofEntries(
            Map.entry("ETHIC", "etik-speak"),
            Map.entry("ATS", "ats"),
            Map.entry("INTERVIEW_EVIDENCE", "ats"),
            Map.entry("ENDPOINT_ADMIN", "endpoint"),
            Map.entry("REPORT", "reporting"),
            Map.entry("MEETING", "meetings"),
            Map.entry("TRANSCRIPT", "meetings"),
            Map.entry("SUGGESTIONS", "suggestions"),
            Map.entry("PURCHASE", "purchase"),
            Map.entry("WAREHOUSE", "warehouse"));

    private ModuleSaleClassification() {
    }

    public static boolean isCore(String moduleKey) {
        return moduleKey != null && CORE.contains(moduleKey);
    }

    /** The SKU that sells this module, empty for core (or unknown) keys. */
    public static Optional<String> sku(String moduleKey) {
        return Optional.ofNullable(moduleKey == null ? null : SELLABLE.get(moduleKey));
    }
}
