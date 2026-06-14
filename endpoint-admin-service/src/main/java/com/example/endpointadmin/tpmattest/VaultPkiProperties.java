package com.example.endpointadmin.tpmattest;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Faz 22.3B (ADR-0039) gate-4b — Vault PKI issuance client configuration.
 *
 * <p><b>Disabled-by-default.</b> With {@code enabled=false} no Vault client is wired and no
 * issuance happens. When enabled, the binding <b>fail-fasts at startup</b> if the AppRole
 * credentials / base URL / pinned CA are missing or the base URL is not HTTPS — there is no
 * silent degrade to an untrusted path (Codex {@code 019ec723}).
 *
 * <p>{@code roleId}/{@code secretId} are sourced from the environment / a mounted secret (ESO),
 * NOT hardcoded. {@link #toString()} redacts the secret + the role id so they can never leak to a log.
 */
@ConfigurationProperties(prefix = "endpoint-admin.tpm-attest.vault")
public record VaultPkiProperties(
        boolean enabled,
        String baseUrl,
        String roleId,
        String secretId,
        String mount,
        String role,
        // The Vault server CA, PEM (build-/config-pinned). The client trusts ONLY this — no system fallback.
        String caCertPem,
        Duration connectTimeout,
        Duration readTimeout,
        Duration tokenRenewSkew,
        int maxResponseBytes) {

    public VaultPkiProperties {
        mount = blank(mount) ? "pki_int" : mount;
        role = blank(role) ? "tpm-device" : role;
        connectTimeout = positiveOr(connectTimeout, Duration.ofSeconds(5));
        readTimeout = positiveOr(readTimeout, Duration.ofSeconds(10));
        tokenRenewSkew = (tokenRenewSkew == null || tokenRenewSkew.isNegative())
                ? Duration.ofSeconds(30) : tokenRenewSkew;
        maxResponseBytes = maxResponseBytes <= 0 ? 256 * 1024 : maxResponseBytes;

        if (enabled) {
            // Fail-fast: an enabled-but-misconfigured client must NOT start (fail-closed, not silent-degrade).
            if (blank(baseUrl) || !baseUrl.toLowerCase().startsWith("https://")) {
                throw new IllegalStateException("vault.baseUrl must be a non-blank https:// URL when enabled");
            }
            if (blank(roleId) || blank(secretId)) {
                throw new IllegalStateException("vault.roleId and vault.secretId are required when enabled");
            }
            if (blank(caCertPem) || !caCertPem.contains("BEGIN CERTIFICATE")) {
                throw new IllegalStateException("vault.caCertPem (pinned Vault CA, PEM) is required when enabled");
            }
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static Duration positiveOr(Duration d, Duration fallback) {
        return (d == null || d.isZero() || d.isNegative()) ? fallback : d;
    }

    /** Redact the AppRole credentials so a property dump / log never leaks them. */
    @Override
    public String toString() {
        return "VaultPkiProperties{enabled=" + enabled
                + ", baseUrl=" + baseUrl
                + ", mount=" + mount + ", role=" + role
                + ", roleId=" + (blank(roleId) ? "<unset>" : "<redacted>")
                + ", secretId=" + (blank(secretId) ? "<unset>" : "<redacted>")
                + ", caCertPem=" + (blank(caCertPem) ? "<unset>" : "<pinned>")
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
                + ", tokenRenewSkew=" + tokenRenewSkew + ", maxResponseBytes=" + maxResponseBytes + "}";
    }
}
