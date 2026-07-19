package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ViewOnlyAuthorityPropertiesTest {

    @Test
    void defaultDisabledContractIsInert() {
        ViewOnlyAuthorityProperties properties = new ViewOnlyAuthorityProperties();
        assertThatNoException().isThrownBy(properties::validateActivation);
    }

    @Test
    void enabledCanonicalDomainsPassStaticActivationValidation() {
        ViewOnlyAuthorityProperties properties = enabledProperties();
        assertThatNoException().isThrownBy(properties::validateActivation);
    }

    @Test
    void alteredOrMissingDomainsFailClosed() {
        ViewOnlyAuthorityProperties properties = enabledProperties();
        properties.setCheckpointCreateIdempotencyDomain("other/checkpoint/v1");
        assertThatThrownBy(properties::validateActivation).isInstanceOf(IllegalStateException.class);

        properties.setCheckpointCreateIdempotencyDomain(
                ViewOnlyAuthorityProperties.CANONICAL_CHECKPOINT_CREATE_IDEMPOTENCY_DOMAIN);
        properties.setJtiDigestDomain("");
        assertThatThrownBy(properties::validateActivation).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabledAuthorityRejectsMissingOrUnpinnedVaultSigner() {
        ViewOnlyAuthorityProperties properties = enabledProperties();
        properties.setVaultTransitKeyId(null);
        assertThatThrownBy(properties::validateActivation)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key ID");

        properties.setVaultTransitKeyId("vault-transit://endpoint-admin/checkpoint#v2");
        assertThatThrownBy(properties::validateActivation)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key ID");
    }

    static ViewOnlyAuthorityProperties enabledProperties() {
        ViewOnlyAuthorityProperties properties = new ViewOnlyAuthorityProperties();
        properties.setEnabled(true);
        properties.setVaultAddress("https://vault.testai.acik.com");
        properties.setVaultTransitMount("transit");
        properties.setVaultTransitKey("view-only-checkpoint");
        properties.setVaultTransitKeyVersion(1);
        properties.setVaultTransitKeyId("vault-transit://endpoint-admin/view-only-checkpoint#v1");
        properties.setVaultTokenFile("/var/run/secrets/vault/token");
        properties.setVaultCaCertificateFile("/var/run/secrets/vault/ca.crt");
        return properties;
    }
}
