package com.example.endpointadmin.remoteaccess.preflight;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.regex.Pattern;

/** Default-off activation boundary for the fixed-function VIEW_ONLY authority. */
@ConfigurationProperties(prefix = "endpoint-admin.view-only-authority")
public class ViewOnlyAuthorityProperties {
    private static final Pattern TRANSIT_KEY_ID =
            Pattern.compile("vault-transit://[a-z0-9/_-]+#v[1-9][0-9]*");
    public static final String CANONICAL_CHECKPOINT_CREATE_IDEMPOTENCY_DOMAIN =
            "faz22.6/view-only/checkpoint-create-idempotency/v1";
    public static final String CANONICAL_OIDC_JTI_DIGEST_DOMAIN =
            "faz22.6/view-only/oidc-jti/v1";

    private boolean enabled;
    private String schema = "public";
    private String issuer = "https://token.actions.githubusercontent.com";
    private String jwksUri = "https://token.actions.githubusercontent.com/.well-known/jwks";
    private String jtiDigestDomain = CANONICAL_OIDC_JTI_DIGEST_DOMAIN;
    private String checkpointCreateIdempotencyDomain = CANONICAL_CHECKPOINT_CREATE_IDEMPOTENCY_DOMAIN;
    private int maximumClockSkewSeconds = 30;
    private String vaultAddress;
    private String vaultTransitMount = "transit";
    private String vaultTransitKey;
    private int vaultTransitKeyVersion = 1;
    private String vaultTransitKeyId;
    private String vaultTokenFile;
    private String vaultCaCertificateFile;
    private int vaultConnectTimeoutSeconds = 5;
    private int vaultRequestTimeoutSeconds = 10;
    private int vaultMaximumResponseBytes = 65_536;

    public void validateActivation() {
        if (!enabled) {
            return;
        }
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]*")) {
            throw invalid("database schema is invalid");
        }
        if (!"https://token.actions.githubusercontent.com".equals(issuer)
                || !"https://token.actions.githubusercontent.com/.well-known/jwks".equals(jwksUri)) {
            throw invalid("GitHub OIDC issuer and JWKS URI must match the signed authority");
        }
        if (!CANONICAL_OIDC_JTI_DIGEST_DOMAIN.equals(jtiDigestDomain)) {
            throw invalid("the domain-bound JTI digest authority does not match the canonical contract");
        }
        if (!CANONICAL_CHECKPOINT_CREATE_IDEMPOTENCY_DOMAIN.equals(checkpointCreateIdempotencyDomain)) {
            throw invalid("the checkpoint-create idempotency domain does not match the canonical contract");
        }
        if (maximumClockSkewSeconds < 0 || maximumClockSkewSeconds > 30) {
            throw invalid("OIDC clock skew must be between zero and 30 seconds");
        }
        requireVaultConfiguration();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(String jwksUri) {
        this.jwksUri = jwksUri;
    }

    public String getJtiDigestDomain() {
        return jtiDigestDomain;
    }

    public void setJtiDigestDomain(String jtiDigestDomain) {
        this.jtiDigestDomain = jtiDigestDomain;
    }

    public String getCheckpointCreateIdempotencyDomain() {
        return checkpointCreateIdempotencyDomain;
    }

    public void setCheckpointCreateIdempotencyDomain(String checkpointCreateIdempotencyDomain) {
        this.checkpointCreateIdempotencyDomain = checkpointCreateIdempotencyDomain;
    }

    public int getMaximumClockSkewSeconds() {
        return maximumClockSkewSeconds;
    }

    public void setMaximumClockSkewSeconds(int maximumClockSkewSeconds) {
        this.maximumClockSkewSeconds = maximumClockSkewSeconds;
    }

    public String getVaultAddress() {
        return vaultAddress;
    }

    public void setVaultAddress(String vaultAddress) {
        this.vaultAddress = vaultAddress;
    }

    public String getVaultTransitMount() {
        return vaultTransitMount;
    }

    public void setVaultTransitMount(String vaultTransitMount) {
        this.vaultTransitMount = vaultTransitMount;
    }

    public String getVaultTransitKey() {
        return vaultTransitKey;
    }

    public void setVaultTransitKey(String vaultTransitKey) {
        this.vaultTransitKey = vaultTransitKey;
    }

    public int getVaultTransitKeyVersion() {
        return vaultTransitKeyVersion;
    }

    public void setVaultTransitKeyVersion(int vaultTransitKeyVersion) {
        this.vaultTransitKeyVersion = vaultTransitKeyVersion;
    }

    public String getVaultTransitKeyId() {
        return vaultTransitKeyId;
    }

    public void setVaultTransitKeyId(String vaultTransitKeyId) {
        this.vaultTransitKeyId = vaultTransitKeyId;
    }

    public String getVaultTokenFile() {
        return vaultTokenFile;
    }

    public void setVaultTokenFile(String vaultTokenFile) {
        this.vaultTokenFile = vaultTokenFile;
    }

    public String getVaultCaCertificateFile() {
        return vaultCaCertificateFile;
    }

    public void setVaultCaCertificateFile(String vaultCaCertificateFile) {
        this.vaultCaCertificateFile = vaultCaCertificateFile;
    }

    public int getVaultConnectTimeoutSeconds() {
        return vaultConnectTimeoutSeconds;
    }

    public void setVaultConnectTimeoutSeconds(int vaultConnectTimeoutSeconds) {
        this.vaultConnectTimeoutSeconds = vaultConnectTimeoutSeconds;
    }

    public int getVaultRequestTimeoutSeconds() {
        return vaultRequestTimeoutSeconds;
    }

    public void setVaultRequestTimeoutSeconds(int vaultRequestTimeoutSeconds) {
        this.vaultRequestTimeoutSeconds = vaultRequestTimeoutSeconds;
    }

    public int getVaultMaximumResponseBytes() {
        return vaultMaximumResponseBytes;
    }

    public void setVaultMaximumResponseBytes(int vaultMaximumResponseBytes) {
        this.vaultMaximumResponseBytes = vaultMaximumResponseBytes;
    }

    private void requireVaultConfiguration() {
        try {
            URI address = URI.create(required(vaultAddress, "Vault address"));
            if (!"https".equalsIgnoreCase(address.getScheme()) || address.getHost() == null
                    || address.getUserInfo() != null || address.getQuery() != null || address.getFragment() != null
                    || (address.getPath() != null && !address.getPath().isEmpty() && !"/".equals(address.getPath()))) {
                throw invalid("Vault address must be an origin-only https URL");
            }
        } catch (IllegalArgumentException invalidUri) {
            throw invalid("Vault address must be an origin-only https URL");
        }
        if (vaultTransitMount == null || !vaultTransitMount.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || vaultTransitKey == null || !vaultTransitKey.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw invalid("Vault Transit mount and key must be fixed safe path segments");
        }
        if (vaultTransitKeyVersion < 1 || vaultTransitKeyVersion > 1_000_000) {
            throw invalid("Vault Transit key version is outside its hard bound");
        }
        if (vaultTransitKeyId == null || !TRANSIT_KEY_ID.matcher(vaultTransitKeyId).matches()
                || !vaultTransitKeyId.endsWith("#v" + vaultTransitKeyVersion)) {
            throw invalid("Vault Transit key ID must pin the configured key version");
        }
        required(vaultTokenFile, "Vault token sink file");
        required(vaultCaCertificateFile, "Vault CA certificate file");
        if (vaultConnectTimeoutSeconds < 1 || vaultConnectTimeoutSeconds > 30
                || vaultRequestTimeoutSeconds < 1 || vaultRequestTimeoutSeconds > 60
                || vaultMaximumResponseBytes < 1_024 || vaultMaximumResponseBytes > 262_144) {
            throw invalid("Vault transport bounds are invalid");
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw invalid(label + " is required");
        }
        return value;
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("VIEW_ONLY authority activation denied: " + message);
    }
}
