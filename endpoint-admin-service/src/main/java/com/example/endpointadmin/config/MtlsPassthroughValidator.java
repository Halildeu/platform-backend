package com.example.endpointadmin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Faz 22.5 Step-2 — fail-closed startup validation for passthrough mTLS
 * (Codex plan-time thread {@code 019ec0f9} "Must Keep" + acceptance conditions).
 *
 * <p>When {@code endpoint-admin.mtls.passthrough.enabled=true} the context MUST
 * NOT start unless the configuration is coherent and secure. Each invalid case
 * throws so a misconfiguration fails fast rather than silently degrading to an
 * insecure mode (no silent runtime force-disable — Codex 019ec0f9 #7).
 */
public class MtlsPassthroughValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MtlsPassthroughValidator.class);
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private final MtlsPassthroughProperties props;
    private final boolean forwardHeaderEnabled;
    private final int serverPort;
    private final int managementPort;

    public MtlsPassthroughValidator(MtlsPassthroughProperties props,
                                    boolean forwardHeaderEnabled,
                                    int serverPort,
                                    int managementPort) {
        this.props = props;
        this.forwardHeaderEnabled = forwardHeaderEnabled;
        this.serverPort = serverPort;
        this.managementPort = managementPort;
    }

    @Override
    public void afterPropertiesSet() {
        if (!props.isEnabled()) {
            return;
        }

        // 1. Mutual exclusion: passthrough and the spoofable forward-header path
        //    cannot coexist (Codex 019ec0f9 #7 — fail, do not silently disable).
        if (forwardHeaderEnabled) {
            throw fail("endpoint-admin.mtls.forward-header.enabled MUST be false when "
                    + "passthrough is enabled (both modes cannot coexist).");
        }

        // 2. Fixed-tenant authority required, valid, non-nil.
        UUID tenant = parseTenant(props.getFixedTenantId());

        // 3. Port distinctness (must not collide with app / management).
        if (props.getPort() <= 0 || props.getPort() > 65535) {
            throw fail("passthrough.port " + props.getPort() + " is not a valid TCP port.");
        }
        if (props.getPort() == serverPort) {
            throw fail("passthrough.port (" + props.getPort() + ") MUST differ from server.port.");
        }
        if (props.getPort() == managementPort) {
            throw fail("passthrough.port (" + props.getPort() + ") MUST differ from management.server.port.");
        }

        // 4. Keystore + truststore present, readable, password set.
        requireReadable("keyStore", props.getKeyStore());
        requireSecret("keyStorePassword", props.getKeyStorePassword());
        requireReadable("trustStore", props.getTrustStore());
        requireSecret("trustStorePassword", props.getTrustStorePassword());

        log.info("Passthrough mTLS ENABLED: port={} fixedTenant={} clientAuth=NEED keyStoreType={} trustStoreType={}. "
                        + "X-Tenant-Id is IGNORED on the mTLS connector; forward-header mode is OFF.",
                props.getPort(), tenant, props.getKeyStoreType(), props.getTrustStoreType());
    }

    private UUID parseTenant(String raw) {
        if (raw == null || raw.isBlank()) {
            throw fail("passthrough.fixed-tenant-id is required when passthrough is enabled.");
        }
        final UUID tenant;
        try {
            tenant = UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw fail("passthrough.fixed-tenant-id '" + raw + "' is not a valid UUID.");
        }
        if (NIL_UUID.equals(tenant)) {
            throw fail("passthrough.fixed-tenant-id MUST NOT be the nil UUID.");
        }
        return tenant;
    }

    private void requireReadable(String name, String pathStr) {
        if (pathStr == null || pathStr.isBlank()) {
            throw fail("passthrough." + name + " is required when passthrough is enabled.");
        }
        Path p = Path.of(pathStr);
        if (!Files.isReadable(p)) {
            throw fail("passthrough." + name + " '" + pathStr + "' does not exist or is not readable.");
        }
    }

    private void requireSecret(String name, String value) {
        if (value == null || value.isEmpty()) {
            throw fail("passthrough." + name + " is required when passthrough is enabled.");
        }
    }

    private IllegalStateException fail(String message) {
        return new IllegalStateException("Invalid passthrough mTLS configuration: " + message);
    }
}
