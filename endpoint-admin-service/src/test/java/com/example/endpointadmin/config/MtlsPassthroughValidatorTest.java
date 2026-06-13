package com.example.endpointadmin.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.5 Step-2 — fail-closed startup validation (Codex {@code 019ec0f9}
 * "Must Keep" acceptance conditions). Each insecure/incoherent passthrough
 * config must prevent context startup.
 */
class MtlsPassthroughValidatorTest {

    private static final int SERVER_PORT = 8096;
    private static final int MGMT_PORT = 8081;
    private static final String VALID_TENANT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private Path keyStore;
    private Path trustStore;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        keyStore = Files.writeString(dir.resolve("ks.p12"), "dummy");
        trustStore = Files.writeString(dir.resolve("ts.p12"), "dummy");
    }

    /** Fully valid, enabled passthrough config. */
    private MtlsPassthroughProperties validEnabled() {
        MtlsPassthroughProperties p = new MtlsPassthroughProperties();
        p.setEnabled(true);
        p.setPort(8443);
        p.setFixedTenantId(VALID_TENANT);
        p.setKeyStore(keyStore.toString());
        p.setKeyStorePassword("secret");
        p.setTrustStore(trustStore.toString());
        p.setTrustStorePassword("secret");
        return p;
    }

    private MtlsPassthroughValidator validator(MtlsPassthroughProperties p, boolean forwardHeader) {
        return new MtlsPassthroughValidator(p, forwardHeader, SERVER_PORT, MGMT_PORT);
    }

    @Test
    void disabled_skipsAllValidation() {
        MtlsPassthroughProperties p = new MtlsPassthroughProperties(); // enabled=false, everything blank
        assertThatCode(() -> validator(p, true).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void validConfig_passes() {
        assertThatCode(() -> validator(validEnabled(), false).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void rejects_forwardHeaderCoexistence() {
        assertThatThrownBy(() -> validator(validEnabled(), true).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forward-header");
    }

    @Test
    void rejects_missingFixedTenant() {
        MtlsPassthroughProperties p = validEnabled();
        p.setFixedTenantId("  ");
        assertThatThrownBy(() -> validator(p, false).afterPropertiesSet())
                .hasMessageContaining("fixed-tenant-id");
    }

    @Test
    void rejects_nilUuid() {
        MtlsPassthroughProperties p = validEnabled();
        p.setFixedTenantId("00000000-0000-0000-0000-000000000000");
        assertThatThrownBy(() -> validator(p, false).afterPropertiesSet())
                .hasMessageContaining("nil UUID");
    }

    @Test
    void rejects_invalidUuid() {
        MtlsPassthroughProperties p = validEnabled();
        p.setFixedTenantId("not-a-uuid");
        assertThatThrownBy(() -> validator(p, false).afterPropertiesSet())
                .hasMessageContaining("not a valid UUID");
    }

    @Test
    void rejects_portCollisionWithServer() {
        MtlsPassthroughProperties p = validEnabled();
        p.setPort(SERVER_PORT);
        assertThatThrownBy(() -> validator(p, false).afterPropertiesSet())
                .hasMessageContaining("server.port");
    }

    @Test
    void rejects_portCollisionWithManagement() {
        MtlsPassthroughProperties p = validEnabled();
        p.setPort(MGMT_PORT);
        assertThatThrownBy(() -> validator(p, false).afterPropertiesSet())
                .hasMessageContaining("management.server.port");
    }

    @Test
    void rejects_invalidPort() {
        MtlsPassthroughProperties p = validEnabled();
        p.setPort(0);
        assertThatThrownBy(() -> validator(p, false).afterPropertiesSet())
                .hasMessageContaining("valid TCP port");
    }

    @Test
    void rejects_missingKeystoreFile() {
        MtlsPassthroughProperties p = validEnabled();
        p.setKeyStore("/no/such/keystore.p12");
        assertThatThrownBy(() -> validator(p, false).afterPropertiesSet())
                .hasMessageContaining("keyStore");
    }

    @Test
    void rejects_missingKeystorePassword() {
        MtlsPassthroughProperties p = validEnabled();
        p.setKeyStorePassword("");
        assertThatThrownBy(() -> validator(p, false).afterPropertiesSet())
                .hasMessageContaining("keyStorePassword");
    }

    @Test
    void rejects_missingTruststore() {
        MtlsPassthroughProperties p = validEnabled();
        p.setTrustStore(null);
        assertThatThrownBy(() -> validator(p, false).afterPropertiesSet())
                .hasMessageContaining("trustStore");
    }
}
