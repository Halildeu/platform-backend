package com.example.endpointadmin.model;

/**
 * Wave-12 PR-5 — risk tier attached to a {@link PolicyChangeApproval},
 * surfaced to reviewers so they can calibrate scrutiny. Carried as
 * {@code PolicyApprovalDomainExtras.riskTier} on the platform-web side.
 */
public enum PolicyRiskTier {
    LOW,
    MEDIUM,
    HIGH
}
