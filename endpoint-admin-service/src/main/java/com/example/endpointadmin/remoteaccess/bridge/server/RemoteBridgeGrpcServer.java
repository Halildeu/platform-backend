package com.example.endpointadmin.remoteaccess.bridge.server;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — the Netty grpc server lifecycle. This bean ONLY exists when
 * {@code remote-bridge.enabled=true} ({@link RemoteBridgeServerConfig} is conditional) — the default
 * application context has no server object, no bind, no scheduler (ADR-0034 disabled-by-default).
 *
 * <p>PLAINTEXT-over-loopback is the T-2b stance: the real mTLS server credentials + the TLS-passthrough L4
 * edge are pilot infrastructure (T-4 + gitops, owner-gated §13/D10). The bind host defaults to 127.0.0.1 so
 * even an enabled dev server is never host-external by default.
 */
public final class RemoteBridgeGrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RemoteBridgeGrpcServer.class);

    private final RemoteBridgeServerProperties properties;
    private final RemoteBridgeConnectService service;
    private final ControlStreamRegistry registry;
    private final ScheduledExecutorService heartbeatScheduler;

    private volatile Server server;

    public RemoteBridgeGrpcServer(RemoteBridgeServerProperties properties,
                                  RemoteBridgeConnectService service,
                                  ControlStreamRegistry registry,
                                  ScheduledExecutorService heartbeatScheduler) {
        this.properties = properties;
        this.service = service;
        this.registry = registry;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Override
    public void start() {
        if (server != null) {
            return;
        }
        try {
            server = NettyServerBuilder
                    .forAddress(new InetSocketAddress(properties.bindHost(), properties.port()))
                    .intercept(new PeerIdentityInterceptor())
                    .addService(service)
                    .build()
                    .start();
            log.info("remote-bridge grpc server listening on {}:{} (mTLS edge = pilot infra; loopback default)",
                    properties.bindHost(), properties.port());
        } catch (IOException e) {
            throw new IllegalStateException("remote-bridge grpc server failed to bind "
                    + properties.bindHost() + ":" + properties.port(), e);
        }
    }

    @Override
    public void stop() {
        Server current = server;
        server = null;
        if (current == null) {
            return;
        }
        registry.completeAll();
        heartbeatScheduler.shutdownNow();
        current.shutdown();
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
