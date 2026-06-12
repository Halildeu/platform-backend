package com.example.endpointadmin.remoteaccess.bridge.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — remote-bridge grpc server configuration. {@code enabled} defaults to FALSE
 * and the entire server configuration class is conditional on it (ADR-0034 disabled-by-default): with the
 * default, NO remote-bridge bean exists — no Netty server object, no bind, no scheduler.
 *
 * <p>{@code bindHost} defaults to loopback — exposing the broker beyond the host is an explicit pilot
 * decision (TLS-passthrough L4 + gitops), never a code default.
 *
 * <p><b>T-2c (Codex 019ebb6c):</b> an ENABLED server requires mutual-TLS credentials ({@link Tls} — file
 * PATHS, K8s secret mounts at the pilot; never inline PEM, never committed material). Plaintext exists only
 * as an explicit, loopback-only test mode ({@code allowInsecurePlaintext}) — a non-loopback plaintext bind
 * refuses to start, so an operator can never accidentally expose an unencrypted broker.
 *
 * @param enabled              master switch; false (default) = no server runtime at all
 * @param bindHost             listen address; loopback by default (pilot overrides explicitly)
 * @param port                 listen port
 * @param heartbeatIntervalMillis CONTROL-stream server-push heartbeat period; {@code <= 0} disables the push
 * @param maxDataFrameBytes    stream-layer cap on {@code DataFrame.payload} size (T-2a deferred this here)
 * @param shutdownGraceMillis  graceful shutdown window before forced termination
 * @param tls                  mutual-TLS credential file paths (all three required together)
 * @param allowInsecurePlaintext explicit loopback-only plaintext escape (tests/local dev); default false
 */
@ConfigurationProperties(prefix = "remote-bridge")
public record RemoteBridgeServerProperties(boolean enabled,
                                           String bindHost,
                                           int port,
                                           long heartbeatIntervalMillis,
                                           int maxDataFrameBytes,
                                           long shutdownGraceMillis,
                                           Tls tls,
                                           boolean allowInsecurePlaintext,
                                           Permit permit) {

    /**
     * Mutual-TLS credential FILE PATHS ({@code -path} suffix — these are never inline PEM bodies). The
     * server's own identity is {@code certChainPemPath}+{@code privateKeyPemPath}; {@code clientCaPemPath}
     * is the device CA that client (agent) certificates must chain to ({@code clientAuth=REQUIRE}).
     * Transport mTLS is IDENTITY only — device TRUST (revocation/EKU/identity decision) stays with the
     * B1.4 {@code CertTrustEvaluator} at the application layer.
     */
    public record Tls(String certChainPemPath, String privateKeyPemPath, String clientCaPemPath) {

        /** All three paths present — the only state in which an enabled server may serve TLS. */
        public boolean isComplete() {
            return notBlank(certChainPemPath) && notBlank(privateKeyPemPath) && notBlank(clientCaPemPath);
        }

        /** Nothing configured at all (as opposed to a partial — and therefore invalid — configuration). */
        public boolean isEmpty() {
            return !notBlank(certChainPemPath) && !notBlank(privateKeyPemPath) && !notBlank(clientCaPemPath);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    /**
     * Faz 22.6 T-4a-ii (Codex 019ebc7e) — the broker's permit-signing key (file PATH, never inline PEM).
     * An ENABLED bridge MUST have this — there is NO insecure escape (unlike the transport-TLS smoke flag):
     * a broker that cannot sign permits cannot authorize any operation, so it must fail closed at startup.
     * {@code signingKeyPemPath} = PKCS#8 EC P-256 private key; {@code kid} = the key id agents pin.
     */
    public record Permit(String signingKeyPemPath, String kid) {

        public boolean isConfigured() {
            return notBlank(signingKeyPemPath) && notBlank(kid);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }

    public RemoteBridgeServerProperties {
        bindHost = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost;
        if (port <= 0) {
            port = 9444;
        }
        if (heartbeatIntervalMillis < 0) {
            heartbeatIntervalMillis = 0;
        }
        if (maxDataFrameBytes <= 0) {
            maxDataFrameBytes = 256 * 1024;
        }
        if (shutdownGraceMillis <= 0) {
            shutdownGraceMillis = 5_000L;
        }
        tls = tls == null ? new Tls(null, null, null) : tls;
        permit = permit == null ? new Permit(null, null) : permit;
    }
}
