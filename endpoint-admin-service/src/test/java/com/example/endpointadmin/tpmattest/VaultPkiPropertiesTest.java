package com.example.endpointadmin.tpmattest;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Faz 22.3B gate-4b — VaultPkiProperties startup fail-fast + defaults + secret redaction. */
class VaultPkiPropertiesTest {

    private static final String CA = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----";

    private static VaultPkiProperties props(boolean enabled, String baseUrl, String roleId,
                                            String secretId, String caPem) {
        return new VaultPkiProperties(enabled, baseUrl, roleId, secretId, null, null, caPem,
                null, null, null, 0);
    }

    @Test
    void disabled_appliesDefaults_noFailFast() {
        VaultPkiProperties p = props(false, null, null, null, null);
        assertThat(p.mount()).isEqualTo("pki_int");
        assertThat(p.role()).isEqualTo("tpm-device");
        assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.maxResponseBytes()).isEqualTo(256 * 1024);
    }

    @Test
    void enabled_withValidConfig_ok() {
        assertThatCode(() -> props(true, "https://vault.local:8200", "rid", "sid", CA))
                .doesNotThrowAnyException();
    }

    @Test
    void enabled_rejectsNonHttpsBaseUrl() {
        assertThatThrownBy(() -> props(true, "http://vault.local:8200", "rid", "sid", CA))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("https://");
    }

    @Test
    void enabled_requiresAppRoleCredentials() {
        assertThatThrownBy(() -> props(true, "https://vault.local", "", "sid", CA))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("roleId");
        assertThatThrownBy(() -> props(true, "https://vault.local", "rid", "", CA))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("roleId");
    }

    @Test
    void enabled_requiresPinnedCa() {
        assertThatThrownBy(() -> props(true, "https://vault.local", "rid", "sid", "not-a-cert"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("caCertPem");
    }

    @Test
    void toString_redactsAppRoleCredentials() {
        String s = props(true, "https://vault.local", "the-role-id", "the-secret-id", CA).toString();
        assertThat(s).doesNotContain("the-role-id").doesNotContain("the-secret-id");
        assertThat(s).contains("<redacted>").contains("https://vault.local");
    }
}
