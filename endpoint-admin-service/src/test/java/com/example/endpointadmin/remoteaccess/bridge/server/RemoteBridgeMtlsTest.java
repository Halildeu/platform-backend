package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.proto.AgentHello;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.RemoteBridgeGrpc;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-2c (Codex 019ebb6c) — REAL mutual-TLS over the REAL Netty transport (no in-process shortcut):
 * a CA-signed client cert handshakes, and the T-2b {@code PeerIdentityInterceptor} seam materializes the
 * authenticated {@code PeerIdentity} (leaf SHA-256 fingerprint) at the control-plane WITHOUT code change —
 * while a certless or wrong-CA client never reaches the seam, and a misconfigured server never binds.
 *
 * <p>All cert material is generated per run ({@link TestMtlsCa}); nothing is committed.
 */
class RemoteBridgeMtlsTest {

    @TempDir
    static Path tempDir;

    private static TestMtlsCa serverCa;
    private static TestMtlsCa clientCa;
    private static TestMtlsCa rogueCa;
    private static TestMtlsCa.Leaf serverLeaf;
    private static TestMtlsCa.Leaf clientLeaf;
    private static Path serverChainPem;
    private static Path serverKeyPem;
    private static Path clientCaPem;
    private static Path serverCaPem;
    private static Path clientCertPem;
    private static Path clientKeyPem;

    private RemoteBridgeGrpcServer server;
    private ManagedChannel channel;
    private final ControlStreamRegistry registry = new ControlStreamRegistry();
    private final RecordingPlane plane = new RecordingPlane();

    @BeforeAll
    static void generatePki() {
        serverCa = TestMtlsCa.create("rb-test-server-ca");
        clientCa = TestMtlsCa.create("rb-test-device-ca");
        rogueCa = TestMtlsCa.create("rb-test-rogue-ca");
        serverLeaf = serverCa.serverLeaf();
        clientLeaf = clientCa.clientLeaf("agent-dev-1");
        serverChainPem = TestMtlsCa.writeCertPem(tempDir, "server-chain.pem", serverLeaf.cert(), serverCa.caCert);
        serverKeyPem = TestMtlsCa.writeKeyPem(tempDir, "server-key.pem", serverLeaf.keys());
        clientCaPem = TestMtlsCa.writeCertPem(tempDir, "client-ca.pem", clientCa.caCert);
        serverCaPem = TestMtlsCa.writeCertPem(tempDir, "server-ca.pem", serverCa.caCert);
        clientCertPem = TestMtlsCa.writeCertPem(tempDir, "client-cert.pem", clientLeaf.cert());
        clientKeyPem = TestMtlsCa.writeKeyPem(tempDir, "client-key.pem", clientLeaf.keys());
    }

    private static final class RecordingPlane implements ControlPlaneHandler {
        final ConcurrentLinkedQueue<RemoteBridgeMessages.AgentHello> hellos = new ConcurrentLinkedQueue<>();
        volatile PeerIdentity lastPeer;

        @Override
        public void onAgentHello(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello) {
            lastPeer = peer;
            hellos.add(hello);
        }

        @Override
        public void onConsentResult(PeerIdentity peer, RemoteBridgeMessages.ConsentResult result) {
        }

        @Override
        public void onAuditEvent(PeerIdentity peer, RemoteBridgeMessages.AuditEvent event) {
        }
    }

    private static final class Sink implements StreamObserver<Envelope> {
        final ConcurrentLinkedQueue<Envelope> received = new ConcurrentLinkedQueue<>();
        final CountDownLatch terminated = new CountDownLatch(1);
        volatile Throwable error;

        @Override
        public void onNext(Envelope envelope) {
            received.add(envelope);
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            terminated.countDown();
        }

        @Override
        public void onCompleted() {
            terminated.countDown();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static RemoteBridgeServerProperties props(int port, RemoteBridgeServerProperties.Tls tls,
                                                      String bindHost, boolean insecure) {
        return new RemoteBridgeServerProperties(true, bindHost, port, 0, 1024, 1000, tls, insecure, null);
    }

    private RemoteBridgeGrpcServer startTlsServer(int port) {
        RemoteBridgeServerProperties properties = props(port,
                new RemoteBridgeServerProperties.Tls(serverChainPem.toString(), serverKeyPem.toString(),
                        clientCaPem.toString()), "127.0.0.1", false);
        RemoteBridgeConnectService service = new RemoteBridgeConnectService(registry, plane,
                DataPlaneHandler.INERT, new SimpleMeterRegistry(), null, 0, 1024,
                System::currentTimeMillis, "rb-v1");
        RemoteBridgeGrpcServer grpcServer = new RemoteBridgeGrpcServer(properties, service, registry);
        grpcServer.start();
        return grpcServer;
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
            channel = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private static Envelope helloEnvelope() {
        return Envelope.newBuilder().setChannelType(ChannelType.CONTROL).setFrameSeq(0)
                .setAgentHello(AgentHello.newBuilder()
                        .setAgentVersion("0.2.3").setDeviceId("dev-1").setCertFingerprint("ab12")
                        .setAttestationEvidenceB64("ZXZpZGVuY2U=").setProtocolVersion("rb-v1"))
                .build();
    }

    private static String sha256Hex(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // the positive path: real handshake → PeerIdentity materializes
    // ------------------------------------------------------------------

    @Test
    void aCaSignedClientHandshakesAndThePeerIdentityMaterializesAtTheSeam() throws Exception {
        int port = freePort();
        server = startTlsServer(port);
        ChannelCredentials creds = TlsChannelCredentials.newBuilder()
                .keyManager(clientCertPem.toFile(), clientKeyPem.toFile())
                .trustManager(serverCaPem.toFile())
                .build();
        channel = Grpc.newChannelBuilder("127.0.0.1:" + port, creds).build();

        Sink sink = new Sink();
        StreamObserver<Envelope> inbound = RemoteBridgeGrpc.newStub(channel).connect(sink);
        inbound.onNext(helloEnvelope());
        inbound.onCompleted();
        assertTrue(sink.terminated.await(5, TimeUnit.SECONDS));

        assertEquals(1, plane.hellos.size(), "the hello must reach the seam over real mTLS");
        assertNotNull(plane.lastPeer);
        // the transport key is the AUTHENTICATED client leaf fingerprint — the T-2b interceptor, unchanged,
        // now fed by a real handshake (the whole point of T-2c)
        assertEquals(sha256Hex(clientLeaf.cert()), plane.lastPeer.transportPeerKey());
    }

    // ------------------------------------------------------------------
    // negatives: certless / wrong-CA never reach the seam
    // ------------------------------------------------------------------

    @Test
    void aCertlessClientFailsTheHandshakeAndNothingReachesTheSeam() throws Exception {
        int port = freePort();
        server = startTlsServer(port);
        ChannelCredentials creds = TlsChannelCredentials.newBuilder()
                .trustManager(serverCaPem.toFile())
                .build(); // no client cert at all — clientAuth=REQUIRE must refuse
        channel = Grpc.newChannelBuilder("127.0.0.1:" + port, creds).build();

        Sink sink = new Sink();
        StreamObserver<Envelope> inbound = RemoteBridgeGrpc.newStub(channel).connect(sink);
        try {
            inbound.onNext(helloEnvelope());
        } catch (RuntimeException ignored) {
            // the stream may already be dead — equally a refusal
        }
        assertTrue(sink.terminated.await(5, TimeUnit.SECONDS));
        assertNotNull(sink.error, "the RPC must fail without a client certificate");
        assertTrue(plane.hellos.isEmpty(), "nothing may reach the control plane");
        assertEquals(0, registry.connectedCount(), "no registry slot may be claimed");
    }

    @Test
    void aClientFromTheWrongCaFailsTheHandshakeAndNothingReachesTheSeam() throws Exception {
        TestMtlsCa.Leaf rogueLeaf = rogueCa.clientLeaf("rogue-agent");
        Path rogueCert = TestMtlsCa.writeCertPem(tempDir, "rogue-cert.pem", rogueLeaf.cert());
        Path rogueKey = TestMtlsCa.writeKeyPem(tempDir, "rogue-key.pem", rogueLeaf.keys());

        int port = freePort();
        server = startTlsServer(port);
        ChannelCredentials creds = TlsChannelCredentials.newBuilder()
                .keyManager(rogueCert.toFile(), rogueKey.toFile())
                .trustManager(serverCaPem.toFile())
                .build();
        channel = Grpc.newChannelBuilder("127.0.0.1:" + port, creds).build();

        Sink sink = new Sink();
        StreamObserver<Envelope> inbound = RemoteBridgeGrpc.newStub(channel).connect(sink);
        try {
            inbound.onNext(helloEnvelope());
        } catch (RuntimeException ignored) {
            // already dead
        }
        assertTrue(sink.terminated.await(5, TimeUnit.SECONDS));
        assertNotNull(sink.error, "a cert from a foreign CA must fail the handshake");
        assertTrue(plane.hellos.isEmpty());
        assertEquals(0, registry.connectedCount());
    }

    // ------------------------------------------------------------------
    // fail-closed startup matrix (no bind on misconfiguration)
    // ------------------------------------------------------------------

    private RemoteBridgeGrpcServer serverWith(RemoteBridgeServerProperties properties) {
        RemoteBridgeConnectService service = new RemoteBridgeConnectService(registry, plane,
                DataPlaneHandler.INERT, new SimpleMeterRegistry(), null, 0, 1024,
                System::currentTimeMillis, "rb-v1");
        return new RemoteBridgeGrpcServer(properties, service, registry);
    }

    @Test
    void enabledWithoutTlsAndWithoutTheInsecureFlagRefusesToStart() throws Exception {
        int port = freePort();
        RemoteBridgeGrpcServer grpcServer = serverWith(props(port, null, "127.0.0.1", false));
        IllegalStateException refusal = assertThrows(IllegalStateException.class, grpcServer::start);
        assertTrue(refusal.getMessage().contains("requires mutual TLS"));
        assertFalse(grpcServer.isRunning(), "no bind may have happened");
        assertTrue(isPortFree(port), "the port must never have been bound");
    }

    @Test
    void partialTlsConfigurationRefusesToStart() throws Exception {
        int port = freePort();
        RemoteBridgeGrpcServer grpcServer = serverWith(props(port,
                new RemoteBridgeServerProperties.Tls(serverChainPem.toString(), null, null), "127.0.0.1", false));
        IllegalStateException refusal = assertThrows(IllegalStateException.class, grpcServer::start);
        assertTrue(refusal.getMessage().contains("PARTIAL"));
        assertFalse(grpcServer.isRunning());
    }

    @Test
    void missingOrUnreadableTlsFilesRefuseToStart() throws Exception {
        int port = freePort();
        RemoteBridgeGrpcServer grpcServer = serverWith(props(port,
                new RemoteBridgeServerProperties.Tls(tempDir.resolve("nope.pem").toString(),
                        serverKeyPem.toString(), clientCaPem.toString()), "127.0.0.1", false));
        IllegalStateException refusal = assertThrows(IllegalStateException.class, grpcServer::start);
        assertTrue(refusal.getMessage().contains("missing or unreadable"));
        assertFalse(grpcServer.isRunning());
    }

    @Test
    void invalidPemContentRefusesToStart() throws Exception {
        Path garbage = Files.writeString(tempDir.resolve("garbage.pem"), "not a pem at all");
        int port = freePort();
        RemoteBridgeGrpcServer grpcServer = serverWith(props(port,
                new RemoteBridgeServerProperties.Tls(garbage.toString(), garbage.toString(),
                        clientCaPem.toString()), "127.0.0.1", false));
        IllegalStateException refusal = assertThrows(IllegalStateException.class, grpcServer::start);
        assertTrue(refusal.getMessage().contains("failed to load"));
        assertFalse(grpcServer.isRunning());
    }

    @Test
    void insecurePlaintextIsAllowedOnlyOnAProvablyLoopbackBind() throws Exception {
        // loopback + flag → starts (the T-2b/test mode)
        int port = freePort();
        RemoteBridgeGrpcServer loopback = serverWith(props(port, null, "127.0.0.1", true));
        loopback.start();
        assertTrue(loopback.isRunning());
        loopback.stop();

        // any non-loopback (or ambiguous) host refuses — IPv4 wildcard, IPv6 wildcard, hostname
        for (String host : new String[] {"0.0.0.0", "::", "localhost", "10.0.0.5"}) {
            RemoteBridgeGrpcServer refused = serverWith(props(freePort(), null, host, true));
            IllegalStateException refusal = assertThrows(IllegalStateException.class, refused::start,
                    "plaintext on '" + host + "' must refuse");
            assertTrue(refusal.getMessage().contains("provably-loopback"));
            assertFalse(refused.isRunning());
        }
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
