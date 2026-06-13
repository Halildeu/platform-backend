package com.example.endpointadmin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Faz 22.5 Step-2 — passthrough mTLS connector + tenant-authority config.
 *
 * <p>ADR-0029 #1501 (passthrough canonical). Codex plan-time design thread
 * {@code 019ec0f9} AGREE. Everything defaults OFF/blank so the secondary
 * {@code :8443} mTLS connector and the connector guard are inert until an
 * overlay explicitly sets {@code endpoint-admin.mtls.passthrough.enabled=true}.
 *
 * <p><b>Tenant authority (security-critical):</b> in passthrough the L4 SNI
 * edge cannot strip/inject {@code X-Tenant-Id}, so that header is
 * client-controlled and MUST NOT be trusted. The tenant is instead a single,
 * fixed UUID ({@link #fixedTenantId}) — a single-company deployment binding,
 * NOT a multi-tenant mechanism. A forged {@code X-Tenant-Id} therefore has zero
 * effect on which tenant a first enrollment lands in.
 */
@ConfigurationProperties(prefix = "endpoint-admin.mtls.passthrough")
public class MtlsPassthroughProperties {

    /**
     * Master switch. When {@code false}: no {@code :8443} connector is added, the
     * connector guard filter is not registered, and header-mode tenant resolution
     * is unchanged. Default {@code false} (Codex 019ec0f9: zero runtime impact
     * until an overlay enables it; enabling is atomic with forward-header-off).
     */
    private boolean enabled = false;

    /** mTLS connector port. MUST differ from server.port and management.server.port. */
    private int port = 8443;

    /**
     * Single-tenant authority for passthrough enrollment. Required (non-blank,
     * valid non-nil UUID) when {@link #enabled} — startup fails otherwise. The
     * {@code X-Tenant-Id} header is ignored on the mTLS connector.
     */
    private String fixedTenantId;

    /** Server keystore (PEM-in-PKCS12 / JKS) holding the backend cert + key. */
    private String keyStore;
    private String keyStorePassword;
    private String keyStoreType = "PKCS12";

    /**
     * Truststore holding ONLY the dedicated endpoint-machine issuing CA /
     * intermediate (NOT a broad enterprise root — Codex 019ec0f9 #11). Test CA
     * locally, AD CS issuing CA at prod activation.
     */
    private String trustStore;
    private String trustStorePassword;
    private String trustStoreType = "PKCS12";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getFixedTenantId() { return fixedTenantId; }
    public void setFixedTenantId(String fixedTenantId) { this.fixedTenantId = fixedTenantId; }

    public String getKeyStore() { return keyStore; }
    public void setKeyStore(String keyStore) { this.keyStore = keyStore; }

    public String getKeyStorePassword() { return keyStorePassword; }
    public void setKeyStorePassword(String keyStorePassword) { this.keyStorePassword = keyStorePassword; }

    public String getKeyStoreType() { return keyStoreType; }
    public void setKeyStoreType(String keyStoreType) { this.keyStoreType = keyStoreType; }

    public String getTrustStore() { return trustStore; }
    public void setTrustStore(String trustStore) { this.trustStore = trustStore; }

    public String getTrustStorePassword() { return trustStorePassword; }
    public void setTrustStorePassword(String trustStorePassword) { this.trustStorePassword = trustStorePassword; }

    public String getTrustStoreType() { return trustStoreType; }
    public void setTrustStoreType(String trustStoreType) { this.trustStoreType = trustStoreType; }
}
