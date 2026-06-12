package com.example.endpointadmin.remoteaccess.bridge.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — the disabled-by-default acceptance: the DEFAULT context has ZERO
 * remote-bridge beans (no server object, no bind, no scheduler, not even the properties holder), and an
 * explicitly-enabled context starts and stops the real Netty server cleanly on loopback.
 *
 * <p>T-4a-ii slice-3c: an enabled context now also wires the durable audit sink + the broker; a mock
 * {@link JdbcTemplate} stands in for the datasource (the {@code DbRecordingSink} constructor only holds the
 * reference, no DB I/O at wiring — its Postgres behaviour is proven in its own IT).
 */
class RemoteBridgeServerLifecycleTest {

    @TempDir
    static Path tempDir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withUserConfiguration(RemoteBridgeServerConfig.class);

    /** A valid PKCS#8 EC P-256 key written to {@code name} (signing + anchor keys share the format). */
    private static String ecKeyPath(String name) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] der = g.generateKeyPair().getPrivate().getEncoded();
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        Path file = tempDir.resolve(name);
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n");
        return file.toString();
    }

    private static String permitKeyPath() throws Exception {
        return ecKeyPath("lifecycle-permit.pem");
    }

    /** T-4a-ii slice-3c: the recording-anchor key is a SEPARATE forensic key from the permit key (Codex S2). */
    private static String anchorKeyPath() throws Exception {
        return ecKeyPath("lifecycle-anchor.pem");
    }

    @Test
    void defaultContextHasNoRemoteBridgeBeansAtAll() {
        runner.run(context -> {
            assertFalse(context.containsBean("remoteBridgeGrpcServer"));
            assertFalse(context.containsBean("remoteBridgeConnectService"));
            assertFalse(context.containsBean("remoteBridgeControlStreamRegistry"));
            assertFalse(context.containsBean("remoteBridgeHeartbeatScheduler"));
            assertFalse(context.containsBean("remoteBridgePeerTrustLedger"));
            assertFalse(context.containsBean("remoteBridgeSessionStore"));
            assertFalse(context.containsBean("remoteBridgeControlPlane"));
            assertFalse(context.containsBean("remoteBridgeInboundAuditSink"));
            assertFalse(context.containsBean("remoteBridgeDurableAuditSink"));
            assertFalse(context.containsBean("remoteBridgeBroker"));
            assertEquals(0, context.getBeanNamesForType(RemoteBridgeServerProperties.class).length);
        });
    }

    @Test
    void explicitlyDisabledBehavesLikeDefault() {
        runner.withPropertyValues("remote-bridge.enabled=false").run(context -> {
            assertFalse(context.containsBean("remoteBridgeGrpcServer"));
            assertFalse(context.containsBean("remoteBridgePeerTrustLedger"));
            assertFalse(context.containsBean("remoteBridgeSessionStore"));
            assertFalse(context.containsBean("remoteBridgeControlPlane"));
            assertFalse(context.containsBean("remoteBridgeDurableAuditSink"));
            assertFalse(context.containsBean("remoteBridgeBroker"));
        });
    }

    @Test
    void enabledWithoutTlsFailsTheContextFailClosed() throws Exception {
        // T-2c: SmartLifecycle start refuses → context refresh fails → nothing serves.
        // T-4a-ii: a valid permit key AND a valid anchor key are supplied so the signer + durable sink + broker
        // all wire cleanly and TLS stays the root cause (an anchor-key failure would otherwise mask it).
        runner.withPropertyValues("remote-bridge.enabled=true",
                "remote-bridge.permit.signing-key-pem-path=" + permitKeyPath(),
                "remote-bridge.permit.kid=kid-1",
                "remote-bridge.recording.anchor-key.path=" + anchorKeyPath()).run(context -> {
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
    void enabledWithoutAnchorKeyFailsClosed() throws Exception {
        // slice-3c: the durable recording-anchor key is mandatory for an enabled broker — a blank path
        // refuses to start (a broker that cannot durably anchor its audit trail must not run).
        runner.withPropertyValues("remote-bridge.enabled=true",
                "remote-bridge.bind-host=127.0.0.1",
                "remote-bridge.allow-insecure-plaintext=true",
                "remote-bridge.permit.signing-key-pem-path=" + permitKeyPath(),
                "remote-bridge.permit.kid=kid-1").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertTrue(failure != null, "the context must fail to start without an anchor key");
            Throwable root = failure;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertTrue(root.getMessage() != null
                            && root.getMessage().contains("remote-bridge.recording.anchor-key.path"),
                    "root cause was: " + root);
        });
    }

    @Test
    void enabledWithBadAnchorAlgorithmFailsClosedAtRefresh() throws Exception {
        // slice-3c (Codex REVISE): the anchor algorithm is PREVALIDATED at bean refresh (a startup probe runs
        // SignatureAlgorithms.require) — a bad algorithm must fail to start, NOT lazily at the first record.
        runner.withPropertyValues("remote-bridge.enabled=true",
                "remote-bridge.bind-host=127.0.0.1",
                "remote-bridge.allow-insecure-plaintext=true",
                "remote-bridge.permit.signing-key-pem-path=" + permitKeyPath(),
                "remote-bridge.permit.kid=kid-1",
                "remote-bridge.recording.anchor-key.path=" + anchorKeyPath(),
                "remote-bridge.recording.anchor-key.algorithm=NOPE-NOT-AN-ALGORITHM").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertTrue(failure != null, "a bad anchor algorithm must fail the context at refresh");
            Throwable root = failure;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            String chain = "";
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t.getMessage() != null) {
                    chain += t.getMessage() + " | ";
                }
            }
            assertTrue(chain.contains("anchor-key.algorithm is invalid"), "cause chain was: " + chain);
        });
    }

    @Test
    void enabledWithAnchorKeyAlgMismatchFailsClosedAtRefresh() throws Exception {
        // slice-3c (Codex REVISE-2): SHA256withRSA is ALLOWED by the signature allowlist but is incompatible
        // with an EC anchor key — the refresh probe runs a real anchor (Signature.initSign), so the key/alg
        // mismatch fails the context at refresh, NOT lazily at the first record.
        runner.withPropertyValues("remote-bridge.enabled=true",
                "remote-bridge.bind-host=127.0.0.1",
                "remote-bridge.allow-insecure-plaintext=true",
                "remote-bridge.permit.signing-key-pem-path=" + permitKeyPath(),
                "remote-bridge.permit.kid=kid-1",
                "remote-bridge.recording.anchor-key.path=" + anchorKeyPath(),
                "remote-bridge.recording.anchor-key.algorithm=SHA256withRSA").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertTrue(failure != null, "an EC anchor key with an RSA algorithm must fail at refresh");
            String chain = "";
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t.getMessage() != null) {
                    chain += t.getMessage() + " | ";
                }
            }
            assertTrue(chain.contains("invalid or incompatible"), "cause chain was: " + chain);
        });
    }

    @Test
    void enabledWithMalformedSchemaFailsClosedAtRefresh() throws Exception {
        // slice-3c (Codex REVISE-3): the DB schema is prevalidated at refresh via DbRecordingSink's
        // [a-z_][a-z0-9_]* SQL-identifier guard — an uppercase/dashed schema fails to start, NOT at the first
        // record (defence against an injected identifier reaching the INSERT table name).
        runner.withPropertyValues("remote-bridge.enabled=true",
                "remote-bridge.bind-host=127.0.0.1",
                "remote-bridge.allow-insecure-plaintext=true",
                "remote-bridge.permit.signing-key-pem-path=" + permitKeyPath(),
                "remote-bridge.permit.kid=kid-1",
                "remote-bridge.recording.anchor-key.path=" + anchorKeyPath(),
                "ENDPOINT_ADMIN_DB_SCHEMA=Bad-Schema").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertTrue(failure != null, "a malformed schema must fail the context at refresh");
            String chain = "";
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t.getMessage() != null) {
                    chain += t.getMessage() + " | ";
                }
            }
            assertTrue(chain.contains("ENDPOINT_ADMIN_DB_SCHEMA is invalid"), "cause chain was: " + chain);
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
                "remote-bridge.permit.kid=kid-1",
                // slice-3c: the durable recording-anchor key is mandatory too
                "remote-bridge.recording.anchor-key.path=" + anchorKeyPath())
                .run(context -> {
                    RemoteBridgeGrpcServer server = context.getBean(RemoteBridgeGrpcServer.class);
                    assertTrue(server.isRunning(), "SmartLifecycle must have started the server");
                    RemoteBridgeServerProperties properties =
                            context.getBean(RemoteBridgeServerProperties.class);
                    assertEquals("127.0.0.1", properties.bindHost());
                    // T-4a-ii slice-2b: the per-peer trust ledger + session store are wired (verifiers
                    // built fail-closed from the shared factory; default IN_MEMORY config → no fail-fast)
                    assertTrue(context.containsBean("remoteBridgePeerTrustLedger"));
                    assertTrue(context.containsBean("remoteBridgeSessionStore"));
                    // T-4a-ii slice-2c: the REAL inbound control plane is wired (INERT→BrokerControlPlane)
                    assertTrue(context.containsBean("remoteBridgeControlPlane"));
                    assertTrue(context.containsBean("remoteBridgeInboundAuditSink"));
                    // T-4a-ii slice-3c: the durable audit sink + the broker are wired (fail-closed, no transport)
                    assertTrue(context.containsBean("remoteBridgeDurableAuditSink"));
                    assertTrue(context.containsBean("remoteBridgeBroker"));
                    server.stop();
                    assertFalse(server.isRunning());
                });
    }
}
