package com.example.endpointadmin.remoteaccess.bridge.server;

import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — the Netty grpc server lifecycle. This bean ONLY exists when
 * {@code remote-bridge.enabled=true} ({@link RemoteBridgeServerConfig} is conditional) — the default
 * application context has no server object, no bind, no scheduler (ADR-0034 disabled-by-default).
 *
 * <p><b>T-2c (Codex 019ebb6c): secure by default.</b> An enabled server REQUIRES complete mutual-TLS
 * credentials ({@code clientAuth=REQUIRE} against the device CA) loaded from the configured file paths —
 * validated BEFORE any builder/bind, so a misconfiguration can never reach a listening socket. Plaintext
 * exists only behind the explicit {@code allow-insecure-plaintext} flag AND a PROVABLY-loopback bind host
 * (literal {@code 127.0.0.1}/{@code ::1} — hostnames and wildcards are refused: what a name resolves to is
 * ambient state, not proof). Transport mTLS authenticates the peer; device TRUST (revocation/EKU) stays
 * with the B1.4 evaluator at the application layer — deliberately NOT a transport concern (one revocation
 * authority, not two). The TLS-passthrough L4 edge + real cert material remain pilot infrastructure
 * (T-4 + gitops, owner-gated §13/D10).
 */
public final class RemoteBridgeGrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RemoteBridgeGrpcServer.class);

    private final RemoteBridgeServerProperties properties;
    private final RemoteBridgeConnectService service;
    private final ControlStreamRegistry registry;

    private volatile Server server;

    public RemoteBridgeGrpcServer(RemoteBridgeServerProperties properties,
                                  RemoteBridgeConnectService service,
                                  ControlStreamRegistry registry) {
        this.properties = properties;
        this.service = service;
        this.registry = registry;
    }

    /** Literal loopback addresses only — a hostname's resolution is ambient state, not proof (Codex T-2c). */
    private static final Set<String> PROVABLY_LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    @Override
    public void start() {
        if (server != null) {
            return;
        }
        ServerCredentials credentials = buildServerCredentials(); // validated BEFORE any builder/bind
        Server built;
        try {
            // the PEM bodies are actually parsed HERE (the credentials object only carries bytes) — a
            // garbage file must fail closed before any bind, with a TLS-shaped error
            built = NettyServerBuilder
                    .forAddress(new InetSocketAddress(properties.bindHost(), properties.port()), credentials)
                    .intercept(new PeerIdentityInterceptor())
                    .addService(service)
                    .build();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "remote-bridge TLS credentials failed to load (invalid PEM?) — refusing to start", e);
        }
        try {
            server = built.start();
            log.info("remote-bridge grpc server listening on {}:{} ({})", properties.bindHost(),
                    properties.port(), properties.tls().isComplete() ? "mutual TLS" : "INSECURE loopback test mode");
        } catch (IOException e) {
            throw new IllegalStateException("remote-bridge grpc server failed to bind "
                    + properties.bindHost() + ":" + properties.port(), e);
        }
    }

    /**
     * The startup credential decision (small, testable factory — NOT a TrustManager seam: revocation
     * authority stays B1.4's, Codex 019ebb6c). Fail-closed: complete TLS → mutual TLS with required client
     * certs; partial TLS → refuse; no TLS → refuse unless the explicit insecure flag AND a provably-loopback
     * bind host.
     */
    ServerCredentials buildServerCredentials() {
        RemoteBridgeServerProperties.Tls tls = properties.tls();
        if (tls.isComplete()) {
            File certChain = readableFile("remote-bridge.tls.cert-chain-pem-path", tls.certChainPemPath());
            File privateKey = readableFile("remote-bridge.tls.private-key-pem-path", tls.privateKeyPemPath());
            File clientCa = readableFile("remote-bridge.tls.client-ca-pem-path", tls.clientCaPemPath());
            try {
                return TlsServerCredentials.newBuilder()
                        .keyManager(certChain, privateKey)
                        .trustManager(clientCa)
                        .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                        .build();
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException(
                        "remote-bridge TLS credentials failed to load (invalid PEM?) — refusing to start", e);
            }
        }
        if (!tls.isEmpty()) {
            throw new IllegalStateException("remote-bridge TLS configuration is PARTIAL — all three of "
                    + "cert-chain-pem-path, private-key-pem-path, client-ca-pem-path are required together");
        }
        if (!properties.allowInsecurePlaintext()) {
            throw new IllegalStateException("remote-bridge is enabled but has no TLS credentials — an enabled "
                    + "broker requires mutual TLS (or the explicit loopback-only allow-insecure-plaintext "
                    + "test flag). Refusing to start.");
        }
        if (!PROVABLY_LOOPBACK.contains(properties.bindHost())) {
            throw new IllegalStateException("allow-insecure-plaintext is restricted to a provably-loopback "
                    + "bind host (127.0.0.1 / ::1); refusing to bind plaintext on '" + properties.bindHost()
                    + "'");
        }
        return InsecureServerCredentials.create();
    }

    private static File readableFile(String property, String path) {
        File file = new File(path);
        if (!file.isFile() || !file.canRead()) {
            throw new IllegalStateException("remote-bridge TLS file for " + property
                    + " is missing or unreadable: " + path);
        }
        return file;
    }

    @Override
    public void stop() {
        Server current = server;
        server = null;
        if (current == null) {
            return;
        }
        registry.completeAll(); // each handle cancels its own heartbeat task; the scheduler bean stays
        current.shutdown();      // alive (Spring owns its destroy) so a SmartLifecycle restart still works

        try {
            if (!current.awaitTermination(properties.shutdownGraceMillis(), TimeUnit.MILLISECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.shutdownNow();
        }
        log.info("remote-bridge grpc server stopped");
    }

    @Override
    public boolean isRunning() {
        Server current = server;
        return current != null && !current.isShutdown();
    }
}
