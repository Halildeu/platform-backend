package com.example.endpointadmin.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 22 #1497 (ADR-0029 §2.5 reconciliation, Codex 019f0056) — config regression guard.
 *
 * <p>The k8s profile MUST default the client-spoofable forwarded-header mTLS mode to OFF
 * (passthrough is the canonical go-live trust model). This pins the {@code application-k8s.yml}
 * default so a future edit cannot silently re-introduce the default-on drift (#316) that #1497
 * reconciled.
 */
class MtlsForwardHeaderDefaultConfigTest {

    @Test
    void k8sProfileDefaultsForwardHeaderOff() throws Exception {
        String yaml;
        try (InputStream in = getClass().getResourceAsStream("/application-k8s.yml")) {
            assertThat(in).as("application-k8s.yml must be on the classpath").isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(yaml)
                .as("forwarded-header mTLS must default OFF in the k8s profile (#1497 — passthrough is canonical)")
                .contains("ENDPOINT_ADMIN_MTLS_FORWARD_HEADER_ENABLED:false")
                .doesNotContain("ENDPOINT_ADMIN_MTLS_FORWARD_HEADER_ENABLED:true");
    }
}
