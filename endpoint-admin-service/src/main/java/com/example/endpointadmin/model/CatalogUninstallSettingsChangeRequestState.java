package com.example.endpointadmin.model;

/**
 * AG-028 Phase 0 — lifecycle states for
 * {@link CatalogUninstallSettingsChangeRequest}.
 *
 * <p>Mirror of catalog DRAFT→APPROVED maker-checker pattern (V7
 * EndpointSoftwareCatalogItem) for flag flips on already-approved rows.
 *
 * <ul>
 *   <li>{@link #PROPOSED} — initial state after the propose endpoint
 *       accepts the request. Awaits a different operator to approve.</li>
 *   <li>{@link #APPROVED} — different operator approved; service layer
 *       transitions to {@link #APPLIED} in the same transaction.</li>
 *   <li>{@link #APPLIED} — terminal success; catalog row flag flipped.</li>
 *   <li>{@link #REJECTED} — terminal reject; {@code reject_reason} populated.</li>
 * </ul>
 *
 * <p>V31 DB CHECK invariants enforce pairing (approved_by &lt;&gt; proposed_by,
 * approval pair, terminal state pairing).
 */
public enum CatalogUninstallSettingsChangeRequestState {
    PROPOSED,
    APPROVED,
    APPLIED,
    REJECTED
}
