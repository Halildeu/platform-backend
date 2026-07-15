package com.example.endpointadmin.remoteaccess.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/** External, GitOps-mounted policy authority and Ed25519 key registry. */
@ConfigurationProperties(prefix = "remote-view-policy")
public record RemoteViewPolicyProperties(
        boolean enabled,
        String tenantPolicySchemaPath,
        String baselineSchemaPath,
        String envelopeSchemaPath,
        String baselinePath,
        long envelopeTtlSeconds,
        String activeKeyId,
        List<SigningKey> signingKeys,
        Set<String> revokedKeyIds) {

    /** verifyUntil is required for non-active overlap keys; UTC ISO-8601. */
    public record SigningKey(String keyId, String privateKeyPkcs8Path, String publicKeyDerPath,
                             String verifyUntil) {
    }

    public RemoteViewPolicyProperties {
        envelopeTtlSeconds = envelopeTtlSeconds <= 0 ? 300 : envelopeTtlSeconds;
        if (envelopeTtlSeconds < 60) {
            throw new IllegalArgumentException("remote-view-policy envelope TTL must be at least 60 seconds");
        }
        signingKeys = signingKeys == null ? List.of() : List.copyOf(signingKeys);
        revokedKeyIds = revokedKeyIds == null ? Set.of() : Set.copyOf(revokedKeyIds);
    }
}
