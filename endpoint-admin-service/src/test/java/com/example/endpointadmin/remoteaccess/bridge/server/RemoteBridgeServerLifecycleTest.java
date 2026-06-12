package com.example.endpointadmin.remoteaccess.bridge.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — the disabled-by-default acceptance: the DEFAULT context has ZERO
 * remote-bridge beans (no server object, no bind, no scheduler, not even the properties holder), and an
 * explicitly-enabled context starts and stops the real Netty server cleanly on loopback.
 */
class RemoteBridgeServerLifecycleTest {

    @TempDir
    static Path tempDir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RemoteBridgeServerConfig.class);

    /** A valid PKCS#8 EC P-256 signing key (T-4a-ii: an enabled bridge now requires one). */
    private static String permitKeyPath() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] der = g.generateKeyPair().getPrivate().getEncoded();
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        Path file = tempDir.resolve("lifecycle-permit.pem");
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n");
        return file.toString();
    }

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
    void enabledWithoutTlsFailsTheContextFailClosed() throws Exception {
        // T-2c: SmartLifecycle start refuses → context refresh fails → nothing serves.
        // T-4a-ii: a valid permit key is supplied so the signer passes and TLS is the root cause.
        runner.withPropertyValues("remote-bridge.enabled=true",
                "remote-bridge.permit.signing-key-pem-path=" + permitKeyPath(),
                "remote-bridge.permit.kid=kid-1").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertTrue(failure != null, "the context must fail to start");
            Throwable root = failure;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertTrue(root.getMessage() != null && root.getMessage().contains("requires mutual TLS"),
                    "root cause was: " + root);
        });
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
                "remote-bridge.heartbeat-interval-millis=0",
                // T-2c: an enabled server is mTLS-only; the Spring smoke uses the explicit loopback-only escape
                "remote-bridge.allow-insecure-plaintext=true",
                // T-4a-ii: the signer is now mandatory for an enabled bridge
                "remote-bridge.permit.signing-key-pem-path=" + permitKeyPath(),
                "remote-bridge.permit.kid=kid-1")
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
