package com.example.endpointadmin.remoteaccess.bridge.server;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — remote-bridge server wiring, conditional on
 * {@code remote-bridge.enabled=true} with NO default ({@code matchIfMissing=false}): the default application
 * context contains ZERO remote-bridge beans — not even the properties holder
 * ({@code @EnableConfigurationProperties} lives INSIDE the conditional class, Codex T-2b). Same disabled-by-
 * default pattern as {@code ScheduledRevocationDriver}.
 *
 * <p>The {@link ControlPlaneHandler} stays {@code INERT} in T-2b — broker/policy wiring (SessionContext
 * assembly, trust evidence, permits) is the owner-gated T-4 slice.
 */
@Configuration
@ConditionalOnProperty(prefix = "remote-bridge", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RemoteBridgeServerProperties.class)
public class RemoteBridgeServerConfig {

    @Bean
    public ControlStreamRegistry remoteBridgeControlStreamRegistry() {
        return new ControlStreamRegistry();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService remoteBridgeHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "remote-bridge-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    public RemoteBridgeConnectService remoteBridgeConnectService(RemoteBridgeServerProperties properties,
                                                                 ControlStreamRegistry registry,
                                                                 ScheduledExecutorService heartbeatScheduler) {
        return new RemoteBridgeConnectService(registry, ControlPlaneHandler.INERT, heartbeatScheduler,
                properties.heartbeatIntervalMillis(), properties.maxDataFrameBytes(),
                System::currentTimeMillis, "rb-v1");
    }

    @Bean
    public RemoteBridgeGrpcServer remoteBridgeGrpcServer(RemoteBridgeServerProperties properties,
                                                         RemoteBridgeConnectService service,
                                                         ControlStreamRegistry registry) {
        return new RemoteBridgeGrpcServer(properties, service, registry);
    }
}
