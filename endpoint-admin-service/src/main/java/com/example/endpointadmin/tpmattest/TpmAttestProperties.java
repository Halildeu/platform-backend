package com.example.endpointadmin.tpmattest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Faz 22.3B (ADR-0039) gate-4 — TPM attestation enrollment configuration.
 *
 * <p><b>Disabled-by-default + per-tenant opt-in</b> (mirrors the backup-dryrun
 * and uninstall surfaces). With {@code enabled=false} every enrollment call is
 * fail-closed ({@link TpmDenyCode#FEATURE_DISABLED}); with an empty
 * {@code allowed-tenant-ids} no tenant can enroll. No live issuance happens
 * until an overlay deliberately flips this on (gated rollout, ADR-0039).
 */
@ConfigurationProperties(prefix = "endpoint-admin.tpm-attest")
public record TpmAttestProperties(
        boolean enabled,
        Set<UUID> allowedTenantIds,
        Duration nonceTtl,
        Duration clockSkew,
        // Build-time pinned manufacturer EK-root SHA-256 fingerprints (lowercase
        // hex). T-10 trust-bootstrap: the bundle is pinned here, NOT runtime-fetched.
        List<String> manufacturerRootSha256,
        // V12 algorithm whitelist (minimums).
        int minRsaBits,
        int minEcBits,
        // V6/T-5/T-6: device/segment risk classes for which a TPM2_Quote PCR
        // policy is MANDATORY (others optional).
        Set<String> pcrMandatoryRiskClasses) {

    public TpmAttestProperties {
        allowedTenantIds = allowedTenantIds == null ? Set.of() : Set.copyOf(allowedTenantIds);
        manufacturerRootSha256 = manufacturerRootSha256 == null ? List.of() : List.copyOf(manufacturerRootSha256);
        pcrMandatoryRiskClasses = pcrMandatoryRiskClasses == null ? Set.of("HIGH") : Set.copyOf(pcrMandatoryRiskClasses);
        // fail-safe bounds (Codex 019ec723): nonceTtl strictly positive, clockSkew non-negative
        nonceTtl = (nonceTtl == null || nonceTtl.isZero() || nonceTtl.isNegative())
                ? Duration.ofMinutes(5) : nonceTtl;
        clockSkew = (clockSkew == null || clockSkew.isNegative())
                ? Duration.ofSeconds(30) : clockSkew;
        minRsaBits = minRsaBits < 3072 ? 3072 : minRsaBits;
        minEcBits = minEcBits < 256 ? 256 : minEcBits;
    }

    /** True only when the feature is enabled AND this tenant has opted in. */
    public boolean enabledForTenant(UUID tenantId) {
        return enabled && tenantId != null && allowedTenantIds.contains(tenantId);
    }

    /** PCR policy is mandatory for the given device risk class (HIGH by default). */
    public boolean pcrMandatoryFor(String riskClass) {
        return riskClass != null && pcrMandatoryRiskClasses.contains(riskClass);
    }
}
