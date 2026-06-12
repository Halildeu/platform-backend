package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.proto.AgentHello;
import com.example.endpointadmin.remoteaccess.bridge.proto.AuditEvent;
import com.example.endpointadmin.remoteaccess.bridge.proto.Capability;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.ConsentResult;
import com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.ErrorFrame;
import com.example.endpointadmin.remoteaccess.bridge.proto.Heartbeat;
import com.example.endpointadmin.remoteaccess.bridge.proto.Kill;
import com.example.endpointadmin.remoteaccess.bridge.proto.OperationPermit;
import com.example.endpointadmin.remoteaccess.bridge.proto.RemoteBridgeGrpc;
import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — in-process grpc acceptance for the transport slice: no anonymous streams,
 * directional payload allowlists, CONTROL/DATA sequencing rules, the DATA byte cap, advisory-hello identity
 * rule, reconnect-replaces, and THE invariant the split exists for: a KILL pushed on CONTROL lands sub-second
 * while the DATA stream is saturated.
 */
class RemoteBridgeConnectServiceTest {

    private static final PeerIdentity PEER =
            new PeerIdentity("peer-fp-1", Optional.of("dev-1"), List.of());

    private Server server;
    private ManagedChannel channel;
    private final ControlStreamRegistry registry = new ControlStreamRegistry();
    private final RecordingControlPlane controlPlane = new RecordingControlPlane();

    /** Test seam: inject a fixed authenticated PeerIdentity (or none) — same context key as the mTLS interceptor. */
    private record InjectIdentity(PeerIdentity identity) implements ServerInterceptor {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                                                                     Metadata headers,
                                                                     ServerCallHandler<ReqT, RespT> next) {
            if (identity == null) {
                return next.startCall(call, headers);
            }
            Context context = Context.current().withValue(PeerIdentityInterceptor.PEER_IDENTITY, identity);
            return Contexts.interceptCall(context, call, headers, next);
        }
    }

    private static final class RecordingControlPlane implements ControlPlaneHandler {
        final ConcurrentLinkedQueue<RemoteBridgeMessages.AgentHello> hellos = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<RemoteBridgeMessages.ConsentResult> consents = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<RemoteBridgeMessages.AuditEvent> audits = new ConcurrentLinkedQueue<>();
        volatile PeerIdentity lastPeer;

        @Override
        public void onAgentHello(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello) {
            lastPeer = peer;
            hellos.add(hello);
        }

        @Override
        public void onConsentResult(PeerIdentity peer, RemoteBridgeMessages.ConsentResult result) {
            consents.add(result);
        }

        @Override
        public void onAuditEvent(PeerIdentity peer, RemoteBridgeMessages.AuditEvent event) {
            audits.add(event);
        }
    }

    /** Client-side observer collecting everything until the stream terminates. */
    private static final class ClientSink implements StreamObserver<Envelope> {
        final ConcurrentLinkedQueue<Envelope> received = new ConcurrentLinkedQueue<>();
        final CountDownLatch terminated = new CountDownLatch(1);
        volatile CountDownLatch killReceived = new CountDownLatch(1);
        final AtomicLong killAtNanos = new AtomicLong();
        volatile Throwable error;

        @Override
        public void onNext(Envelope envelope) {
            received.add(envelope);
            if (envelope.getPayloadCase() == Envelope.PayloadCase.KILL) {
                killAtNanos.set(System.nanoTime());
                killReceived.countDown();
            }
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

        Envelope firstError() {
            return received.stream()
                    .filter(e -> e.getPayloadCase() == Envelope.PayloadCase.ERROR)
                    .findFirst().orElse(null);
        }
    }

    private RemoteBridgeGrpc.RemoteBridgeStub start(PeerIdentity identity, long heartbeatMillis,
                                                    int maxFrameBytes) throws Exception {
        String name = InProcessServerBuilder.generateName();
        RemoteBridgeConnectService service = new RemoteBridgeConnectService(registry, controlPlane,
                null, heartbeatMillis, maxFrameBytes, System::currentTimeMillis, "rb-v1");
        server = InProcessServerBuilder.forName(name).directExecutor()
                .intercept(new InjectIdentity(identity))
                .addService(service)
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return RemoteBridgeGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // builders
    // ------------------------------------------------------------------

    private static Envelope control(long seq, Envelope.Builder payload) {
        return payload.setChannelType(ChannelType.CONTROL).setFrameSeq(seq).build();
    }

    private static Envelope.Builder hello(String deviceId) {
        return Envelope.newBuilder().setAgentHello(AgentHello.newBuilder()
                .setAgentVersion("0.2.3").setDeviceId(deviceId).setCertFingerprint("ab12")
                .setAttestationEvidenceB64("ZXZpZGVuY2U=").setProtocolVersion("rb-v1")
                .addAdvertisedCapabilities(Capability.VIEW_ONLY));
    }

    private static Envelope.Builder heartbeat() {
        return Envelope.newBuilder().setHeartbeat(Heartbeat.newBuilder().setHeartbeatIntervalMillis(5000));
    }

    private static Envelope dataFrame(String streamId, long seq, int bytes) {
        return Envelope.newBuilder().setChannelType(ChannelType.DATA)
                .setDataFrame(DataFrame.newBuilder().setStreamId(streamId).setFrameSeq(seq)
                        .setContentType("application/octet-stream")
                        .setPayload(ByteString.copyFrom(new byte[bytes])))
                .build();
    }

    // ------------------------------------------------------------------
    // anonymous + allowlist + identity
    // ------------------------------------------------------------------

    @Test
    void anonymousControlIsRefusedBeforeAnyPayload() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(null, 0, 1024);
        ClientSink sink = new ClientSink();
        stub.connect(sink);
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertNotNull(sink.firstError());
        assertEquals("anonymous-peer", sink.firstError().getError().getCode());
        assertEquals(0, registry.connectedCount()); // never registered
    }

    @Test
    void anonymousDataIsRefusedToo() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(null, 0, 1024);
        ClientSink sink = new ClientSink();
        stub.data(sink);
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("anonymous-peer", sink.firstError().getError().getCode());
    }

    @Test
    void agentCannotSendBrokerOriginatedPayloadsInbound() throws Exception {
        // a semi-trusted agent pushing a Kill (or a permit) must be refused — broker authority is not injectable
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024);
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.connect(sink);
        inbound.onNext(control(0, Envelope.newBuilder().setKill(
                Kill.newBuilder().setSessionId("sess-1").setKillReason("fake").setIssuedAtEpochMillis(1))));
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("control-inbound-payload-refused", sink.firstError().getError().getCode());

        ClientSink sink2 = new ClientSink();
        StreamObserver<Envelope> inbound2 = stub.connect(sink2);
        inbound2.onNext(control(0, Envelope.newBuilder().setOperationPermit(
                OperationPermit.newBuilder().setAlg("SHA256withECDSA").setKid("kid-1").setPermitVersion(1)
                        .setPolicyVersion("p1").setDecisionId("d1").setSessionId("sess-1").setOperationId("op-1")
                        .setDeviceId("dev-1").setOperatorSubject("op@x").setCapability(Capability.VIEW_ONLY)
                        .setIssuedAtEpochMillis(1).setExpiresAtEpochMillis(2).setSignatureB64("c2ln"))));
        assertTrue(sink2.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("control-inbound-payload-refused", sink2.firstError().getError().getCode());
    }

    @Test
    void advisoryHelloContradictingTheCertificateClosesTheStream() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024); // cert-bound device = dev-1
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.connect(sink);
        inbound.onNext(control(0, hello("dev-OTHER")));
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("agent-hello-device-id-contradicts-certificate", sink.firstError().getError().getCode());
        assertTrue(controlPlane.hellos.isEmpty()); // never reached the seam
        assertEquals(0, registry.connectedCount());
    }

    @Test
    void validControlPayloadsReachTheSeamWithTheAuthenticatedPeer() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024);
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.connect(sink);
        inbound.onNext(control(0, hello("dev-1")));
        inbound.onNext(control(1, Envelope.newBuilder().setConsentResult(ConsentResult.newBuilder()
                .setSessionId("sess-1").setGranted(true).setWindowsInteractiveSession("Console")
                .setGrantedAtEpochMillis(1000).setExpiryEpochMillis(2000))));
        inbound.onNext(control(2, Envelope.newBuilder().setAuditEvent(AuditEvent.newBuilder()
                .setSessionId("sess-1").setEventType("LOCAL_ABORT").setEpochMillis(1500))));
        inbound.onCompleted();
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertNull(sink.firstError());
        assertEquals(1, controlPlane.hellos.size());
        assertEquals(1, controlPlane.consents.size());
        assertEquals(1, controlPlane.audits.size());
        assertEquals("peer-fp-1", controlPlane.lastPeer.transportPeerKey()); // seam sees the AUTHENTICATED identity
    }

    // ------------------------------------------------------------------
    // sequencing
    // ------------------------------------------------------------------

    @Test
    void controlFrameSeqMustBeStrictlyMonotonicFromZero() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024);
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.connect(sink);
        inbound.onNext(control(0, heartbeat()));
        inbound.onNext(control(1, heartbeat()));
        inbound.onNext(control(1, heartbeat())); // replay
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("control-frame-seq", sink.firstError().getError().getCode());
    }

    @Test
    void dataSequencingAuthorityIsTheDataFrameNotTheEnvelope() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024);
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.data(sink);
        inbound.onNext(dataFrame("st-1", 0, 8));
        inbound.onNext(dataFrame("st-1", 1, 8));
        inbound.onNext(dataFrame("st-2", 0, 8)); // independent per-stream counter
        inbound.onNext(dataFrame("st-1", 1, 8)); // replay on st-1
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("data-frame-seq", sink.firstError().getError().getCode());
    }

    @Test
    void dataEnvelopeCounterMustStayZeroAndStreamIdMustMatch() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024);
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.data(sink);
        inbound.onNext(dataFrame("st-1", 0, 8).toBuilder().setFrameSeq(5).build());
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("data-envelope-frame-seq-must-be-zero", sink.firstError().getError().getCode());

        ClientSink sink2 = new ClientSink();
        StreamObserver<Envelope> inbound2 = stub.data(sink2);
        inbound2.onNext(dataFrame("st-1", 0, 8).toBuilder().setStreamId("st-OTHER").build());
        assertTrue(sink2.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("data-stream-id-mismatch", sink2.firstError().getError().getCode());
    }

    @Test
    void oversizedDataFrameClosesTheStream() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 64);
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.data(sink);
        inbound.onNext(dataFrame("st-1", 0, 65));
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("data-frame-too-large", sink.firstError().getError().getCode());
    }

    @Test
    void controlPayloadOnTheDataStreamIsRefused() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024);
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.data(sink);
        inbound.onNext(hello("dev-1").setChannelType(ChannelType.DATA).build());
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        // T-2a envelope rule fires first: agent_hello is not a DATA payload at all
        assertEquals("envelope-payload-channel-mismatch", sink.firstError().getError().getCode());
    }

    // ------------------------------------------------------------------
    // registry + KILL
    // ------------------------------------------------------------------

    @Test
    void reconnectReplacesOnlyTheSameAuthenticatedPeersStream() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024);
        ClientSink first = new ClientSink();
        stub.connect(first);
        assertEquals(1, registry.connectedCount());

        ClientSink second = new ClientSink();
        stub.connect(second);
        // the old stream was completed by the replace; the new one is live
        assertTrue(first.terminated.await(2, TimeUnit.SECONDS));
        assertEquals(1, registry.connectedCount());
        assertTrue(registry.isConnected("peer-fp-1"));
    }

    @Test
    void killOnControlLandsSubSecondWhileDataIsSaturated() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 64 * 1024);
        ClientSink controlSink = new ClientSink();
        stub.connect(controlSink);
        assertTrue(registry.isConnected("peer-fp-1"));

        // saturate DATA: a writer thread pumping frames as fast as the stream accepts them
        ClientSink dataSink = new ClientSink();
        StreamObserver<Envelope> dataInbound = stub.data(dataSink);
        AtomicBoolean pumping = new AtomicBoolean(true);
        Thread pump = new Thread(() -> {
            long seq = 0;
            while (pumping.get()) {
                try {
                    dataInbound.onNext(dataFrame("st-1", seq++, 16 * 1024));
                } catch (RuntimeException e) {
                    return; // stream closed at shutdown
                }
            }
        }, "data-pump");
        pump.start();
        try {
            Thread.sleep(100); // let the pump establish sustained DATA traffic

            long killSentAt = System.nanoTime();
            assertTrue(registry.killPeer("peer-fp-1", "sess-1", "test-kill", System.currentTimeMillis()));
            assertTrue(controlSink.killReceived.await(1, TimeUnit.SECONDS),
                    "KILL must land on CONTROL within 1s despite DATA saturation");
            long latencyMillis = (controlSink.killAtNanos.get() - killSentAt) / 1_000_000;
            assertTrue(latencyMillis < 1000, "KILL latency was " + latencyMillis + "ms");

            Envelope kill = controlSink.received.stream()
                    .filter(e -> e.getPayloadCase() == Envelope.PayloadCase.KILL).findFirst().orElseThrow();
            assertEquals("sess-1", kill.getKill().getSessionId());
            assertEquals("test-kill", kill.getKill().getKillReason());
            // KILL is terminal: the CONTROL stream completes and the registry slot is gone
            assertTrue(controlSink.terminated.await(2, TimeUnit.SECONDS));
            assertFalse(registry.isConnected("peer-fp-1"));
        } finally {
            pumping.set(false);
            pump.join(2000);
        }
    }

    @Test
    void killForAnUnknownPeerReturnsFalse() {
        assertFalse(registry.killPeer("nobody", null, "reason", 1000L));
    }

    // ------------------------------------------------------------------
    // Codex post-impl P1 — malformed-but-direction-allowed payloads close the stream
    // ------------------------------------------------------------------

    @Test
    void malformedConsentResultClosesTheStreamAndNeverReachesTheSeam() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024);
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.connect(sink);
        inbound.onNext(control(0, Envelope.newBuilder().setConsentResult(ConsentResult.newBuilder()
                .setSessionId("bad session id") // space — fails the wire-id allowlist
                .setGranted(true).setWindowsInteractiveSession("Console")
                .setGrantedAtEpochMillis(1000).setExpiryEpochMillis(2000))));
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("consent-result-session-id", sink.firstError().getError().getCode());
        assertTrue(controlPlane.consents.isEmpty());

        ClientSink sink2 = new ClientSink();
        StreamObserver<Envelope> inbound2 = stub.connect(sink2);
        inbound2.onNext(control(0, Envelope.newBuilder().setConsentResult(ConsentResult.newBuilder()
                .setSessionId("sess-1").setGranted(true).setWindowsInteractiveSession("") // required
                .setGrantedAtEpochMillis(1000).setExpiryEpochMillis(2000))));
        assertTrue(sink2.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("consent-result-interactive-session", sink2.firstError().getError().getCode());
        assertTrue(controlPlane.consents.isEmpty());
    }

    @Test
    void malformedAuditEventClosesTheStreamAndNeverReachesTheSeam() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024);
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.connect(sink);
        inbound.onNext(control(0, Envelope.newBuilder().setAuditEvent(AuditEvent.newBuilder()
                .setSessionId("sess-1").setEventType("LOCAL_ABORT")
                .setContentHash("not-a-sha256").setEpochMillis(1500))));
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("audit-event-content-hash", sink.firstError().getError().getCode());
        assertTrue(controlPlane.audits.isEmpty());
    }

    // ------------------------------------------------------------------
    // Codex post-impl P2 — serialized CONTROL writes + heartbeat lifecycle
    // ------------------------------------------------------------------

    @Test
    void reconnectCancelsTheReplacedStreamsHeartbeat() throws Exception {
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            String name = InProcessServerBuilder.generateName();
            RemoteBridgeConnectService service = new RemoteBridgeConnectService(registry, controlPlane,
                    scheduler, 30, 1024, System::currentTimeMillis, "rb-v1");
            server = InProcessServerBuilder.forName(name).directExecutor()
                    .intercept(new InjectIdentity(PEER)).addService(service).build().start();
            channel = InProcessChannelBuilder.forName(name).directExecutor().build();
            RemoteBridgeGrpc.RemoteBridgeStub stub = RemoteBridgeGrpc.newStub(channel);

            ClientSink first = new ClientSink();
            stub.connect(first);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && first.received.stream()
                    .noneMatch(e -> e.getPayloadCase() == Envelope.PayloadCase.HEARTBEAT)) {
                Thread.sleep(10);
            }
            assertTrue(first.received.stream()
                    .anyMatch(e -> e.getPayloadCase() == Envelope.PayloadCase.HEARTBEAT));

            ClientSink second = new ClientSink();
            stub.connect(second); // replaces the first stream — must cancel ITS heartbeat task too
            assertTrue(first.terminated.await(2, TimeUnit.SECONDS));
            int firstCountAfterReplace = first.received.size();
            Thread.sleep(150); // several would-be heartbeat periods
            assertEquals(firstCountAfterReplace, first.received.size(),
                    "a replaced stream's heartbeat task must stop writing");
            assertTrue(registry.isConnected("peer-fp-1")); // the successor is live
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void killDuringActiveHeartbeatIsCleanAndTerminal() throws Exception {
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            String name = InProcessServerBuilder.generateName();
            RemoteBridgeConnectService service = new RemoteBridgeConnectService(registry, controlPlane,
                    scheduler, 1, 1024, System::currentTimeMillis, "rb-v1"); // aggressive heartbeat
            server = InProcessServerBuilder.forName(name).directExecutor()
                    .intercept(new InjectIdentity(PEER)).addService(service).build().start();
            channel = InProcessChannelBuilder.forName(name).directExecutor().build();

            ClientSink sink = new ClientSink();
            RemoteBridgeGrpc.newStub(channel).connect(sink);
            Thread.sleep(30); // heartbeats flowing
            assertTrue(registry.killPeer("peer-fp-1", "sess-1", "duress", System.currentTimeMillis()));
            assertTrue(sink.killReceived.await(1, TimeUnit.SECONDS));
            assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
            assertFalse(registry.isConnected("peer-fp-1"));
            int countAtKill = sink.received.size();
            Thread.sleep(50); // the killed stream's heartbeat task must be cancelled with the handle
            assertEquals(countAtKill, sink.received.size(),
                    "no writes may follow a kill-closed stream");
        } finally {
            scheduler.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // heartbeat
    // ------------------------------------------------------------------

    @Test
    void serverPushesHeartbeatsOnControl() throws Exception {
        java.util.concurrent.ScheduledExecutorService scheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            String name = InProcessServerBuilder.generateName();
            RemoteBridgeConnectService service = new RemoteBridgeConnectService(registry, controlPlane,
                    scheduler, 50, 1024, System::currentTimeMillis, "rb-v1");
            server = InProcessServerBuilder.forName(name).directExecutor()
                    .intercept(new InjectIdentity(PEER)).addService(service).build().start();
            channel = InProcessChannelBuilder.forName(name).directExecutor().build();

            ClientSink sink = new ClientSink();
            RemoteBridgeGrpc.newStub(channel).connect(sink);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline && sink.received.stream()
                    .noneMatch(e -> e.getPayloadCase() == Envelope.PayloadCase.HEARTBEAT)) {
                Thread.sleep(20);
            }
            Envelope heartbeat = sink.received.stream()
                    .filter(e -> e.getPayloadCase() == Envelope.PayloadCase.HEARTBEAT)
                    .findFirst().orElseThrow();
            assertEquals(ChannelType.CONTROL, heartbeat.getChannelType());
            assertEquals(50, heartbeat.getHeartbeat().getHeartbeatIntervalMillis());
            assertEquals(0, heartbeat.getHeartbeat().getLeaseExpiresAtEpochMillis()); // broker lease = T-4
        } finally {
            scheduler.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // T-2a envelope validation still guards the stream
    // ------------------------------------------------------------------

    @Test
    void adapterLevelEnvelopeDefectsCloseTheStream() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024);
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.connect(sink);
        inbound.onNext(Envelope.newBuilder() // no channel, no payload
                .setChannelType(ChannelType.CHANNEL_TYPE_UNSPECIFIED).build());
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        assertEquals("envelope-channel", sink.firstError().getError().getCode());
    }

    @Test
    void errorFrameEnvelopesUseTheStreamsOwnChannel() throws Exception {
        RemoteBridgeGrpc.RemoteBridgeStub stub = start(PEER, 0, 1024);
        ClientSink sink = new ClientSink();
        StreamObserver<Envelope> inbound = stub.data(sink);
        inbound.onNext(dataFrame("st-1", 7, 8)); // wrong first seq
        assertTrue(sink.terminated.await(2, TimeUnit.SECONDS));
        Envelope error = sink.firstError();
        assertEquals(ChannelType.DATA, error.getChannelType());
        assertTrue(RemoteBridgeProtoAdapterChannelCheck.valid(error));
    }

    /** The outbound ErrorFrame itself must satisfy the T-2a wire contract. */
    private static final class RemoteBridgeProtoAdapterChannelCheck {
        static boolean valid(Envelope envelope) {
            return com.example.endpointadmin.remoteaccess.bridge.wire.RemoteBridgeProtoAdapter
                    .validateEnvelope(envelope).isOk();
        }
    }
}
