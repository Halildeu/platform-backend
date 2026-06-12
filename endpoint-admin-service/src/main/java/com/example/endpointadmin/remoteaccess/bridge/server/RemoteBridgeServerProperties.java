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
 * @param enabled              master switch; false (default) = no server runtime at all
 * @param bindHost             listen address; loopback by default (pilot overrides explicitly)
 * @param port                 listen port
 * @param heartbeatIntervalMillis CONTROL-stream server-push heartbeat period; {@code <= 0} disables the push
 * @param maxDataFrameBytes    stream-layer cap on {@code DataFrame.payload} size (T-2a deferred this here)
 * @param shutdownGraceMillis  graceful shutdown window before forced termination
 */
@ConfigurationProperties(prefix = "remote-bridge")
public record RemoteBridgeServerProperties(boolean enabled,
                                           String bindHost,
                                           int port,
                                           long heartbeatIntervalMillis,
                                           int maxDataFrameBytes,
                                           long shutdownGraceMillis) {

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
    }
}
