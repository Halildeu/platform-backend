package com.example.endpointadmin.remoteaccess.bridge.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — the disabled-by-default acceptance: the DEFAULT context has ZERO
 * remote-bridge beans (no server object, no bind, no scheduler, not even the properties holder), and an
 * explicitly-enabled context starts and stops the real Netty server cleanly on loopback.
 */
class RemoteBridgeServerLifecycleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RemoteBridgeServerConfig.class);

    @Test
    void defaultContextHasNoRemoteBridgeBeansAtAll() {
        runner.run(context -> {
            assertFalse(context.containsBean("remoteBridgeGrpcServer"));
            assertFalse(context.containsBean("remoteBridgeConnectService"));
            assertFalse(context.containsBean("remoteBridgeControlStreamRegistry"));
            assertFalse(context.containsBean("remoteBridgeHeartbeatScheduler"));
            assertEquals(0, context.getBeanNamesForType(RemoteBridgeServerProperties.class).length);
        });
    }

    @Test
    void explicitlyDisabledBehavesLikeDefault() {
        runner.withPropertyValues("remote-bridge.enabled=false").run(context ->
                assertFalse(context.containsBean("remoteBridgeGrpcServer")));
    }

    @Test
    void enabledContextStartsAndStopsTheRealNettyServerOnLoopback() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        runner.withPropertyValues(
                "remote-bridge.enabled=true",
                "remote-bridge.bind-host=127.0.0.1",
                "remote-bridge.port=" + port,
                "remote-bridge.heartbeat-interval-millis=0")
                .run(context -> {
                    RemoteBridgeGrpcServer server = context.getBean(RemoteBridgeGrpcServer.class);
                    assertTrue(server.isRunning(), "SmartLifecycle must have started the server");
                    RemoteBridgeServerProperties properties =
                            context.getBean(RemoteBridgeServerProperties.class);
                    assertEquals("127.0.0.1", properties.bindHost());
                    server.stop();
                    assertFalse(server.isRunning());
                });
    }
}
