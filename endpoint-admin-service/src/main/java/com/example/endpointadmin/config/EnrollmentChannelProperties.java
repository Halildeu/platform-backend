package com.example.endpointadmin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Faz 22.3B (ADR-0039) gate-4c — pins for the dual enrollment-channel resolver
 * ({@code com.example.endpointadmin.security.EnrollmentChannelResolver}).
 *
 * <p><b>Disabled-by-default.</b> With {@code enabled=false} (the default) no
 * resolver bean is created and the AD CS auth path is untouched — the Vault-TPM
 * channel has zero effect until an overlay deliberately flips this on AND the
 * gate-4c-2 wiring injects the resolver (staged rollout, ADR-0039). The pinned
 * SPKI SHA-256 lists are the trust set; the actual issuer certs are supplied as
 * PEM bundles (config {@code *-issuer-pems}) and verified against these pins at
 * startup, so the trust set can never silently widen.
 *
 * <p>Each channel takes a {@code current+next} list to allow issuer-key rotation
 * without a flag-day.
 */
@ConfigurationProperties(prefix = "endpoint-admin.enrollment-channel")
public record EnrollmentChannelProperties(
        boolean enabled,
        // lowercase-hex SHA-256(SubjectPublicKeyInfo) of the allowed AD CS issuer key(s)
        List<String> adCsIssuerSpkiSha256,
        // lowercase-hex SHA-256(SubjectPublicKeyInfo) of the allowed Vault PKI issuer key(s)
        List<String> vaultIssuerSpkiSha256) {

    public EnrollmentChannelProperties {
        adCsIssuerSpkiSha256 = adCsIssuerSpkiSha256 == null ? List.of() : List.copyOf(adCsIssuerSpkiSha256);
        vaultIssuerSpkiSha256 = vaultIssuerSpkiSha256 == null ? List.of() : List.copyOf(vaultIssuerSpkiSha256);
    }
}
