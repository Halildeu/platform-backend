package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.ErrorFrame;
import com.example.endpointadmin.remoteaccess.bridge.proto.Heartbeat;
import com.example.endpointadmin.remoteaccess.bridge.proto.RemoteBridgeGrpc;
import com.example.endpointadmin.remoteaccess.bridge.wire.DecodeResult;
import com.example.endpointadmin.remoteaccess.bridge.wire.RemoteBridgeProtoAdapter;
import io.grpc.stub.StreamObserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — the {@code RemoteBridge} bidi service: Connect = CONTROL, Data = DATA.
 * Transport-only: every inbound envelope passes the T-2a adapter validation PLUS the T-2b stream rules, then
 * CONTROL payloads are decoded to domain records and handed to the {@link ControlPlaneHandler} seam
 * ({@code INERT} in T-2b — no broker, no policy, no persistence). Fail-closed everywhere: a violation gets
 * one {@link ErrorFrame} and the stream is closed.
 *
 * <p><b>Stream rules (Codex T-2b REVISE absorbed):</b>
 * <ul>
 *   <li><b>No anonymous streams:</b> a Connect/Data without an authenticated {@link PeerIdentity} (mTLS in
 *       production, injected context in tests) is refused before any payload is read.</li>
 *   <li><b>Directional allowlist:</b> inbound (agent→broker) CONTROL accepts ONLY AgentHello / ConsentResult /
 *       AuditEvent / Heartbeat / ErrorFrame. The broker-originated payloads (OperationPermit, ConsentPrompt,
 *       Kill — and the operator-console payloads SessionRequest/OperationRequest, which do NOT ride the agent
 *       tunnel) are REFUSED inbound, so a semi-trusted agent can never inject broker authority.</li>
 *   <li><b>Sequencing:</b> CONTROL uses {@code Envelope.frameSeq}, strictly increasing from 0 per stream.
 *       DATA sequencing authority is {@code DataFrame.frameSeq} per {@code DataFrame.streamId} (proto3 int64
 *       has no presence, so the envelope counter cannot be optional-or-matching); the DATA
 *       {@code Envelope.frameSeq} MUST stay 0 and {@code Envelope.streamId} must be empty or equal to the
 *       frame's.</li>
 *   <li><b>Size cap:</b> {@code DataFrame.payload} larger than the configured max closes the stream (the
 *       stream layer owns the byte cap — deferred here from T-2a by design).</li>
 *   <li><b>AgentHello identity rule:</b> an advisory {@code AgentHello.deviceId} that CONTRADICTS the
 *       cert-bound device id closes the stream (fail-closed; advisory data may be ignored, never believed
 *       over the certificate).</li>
 * </ul>
 *
 * <p>Heartbeat: server-push on CONTROL only, period from properties ({@code <= 0} disables — tests drive it
 * deterministically); {@code leaseExpiresAt} stays 0 until the broker lease wiring (T-4).
 */
public final class RemoteBridgeConnectService extends RemoteBridgeGrpc.RemoteBridgeImplBase {

    private final ControlStreamRegistry registry;
    private final ControlPlaneHandler controlPlane;
    private final ScheduledExecutorService heartbeatScheduler;
    private final long heartbeatIntervalMillis;
    private final int maxDataFrameBytes;
    private final LongSupplier clock;
    private final String protocolVersion;

    public RemoteBridgeConnectService(ControlStreamRegistry registry,
                                      ControlPlaneHandler controlPlane,
                                      ScheduledExecutorService heartbeatScheduler,
                                      long heartbeatIntervalMillis,
                                      int maxDataFrameBytes,
                                      LongSupplier clock,
                                      String protocolVersion) {
        if (registry == null || controlPlane == null || clock == null) {
            throw new IllegalArgumentException("registry, controlPlane and clock are required");
        }
        if (maxDataFrameBytes <= 0) {
            throw new IllegalArgumentException("maxDataFrameBytes must be positive");
        }
        this.registry = registry;
        this.controlPlane = controlPlane;
        this.heartbeatScheduler = heartbeatScheduler;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        this.maxDataFrameBytes = maxDataFrameBytes;
        this.clock = clock;
        this.protocolVersion = protocolVersion == null || protocolVersion.isBlank() ? "rb-v1" : protocolVersion;
    }

    // ------------------------------------------------------------------
    // CONTROL
    // ------------------------------------------------------------------

    @Override
    public StreamObserver<Envelope> connect(StreamObserver<Envelope> outbound) {
        PeerIdentity peer = PeerIdentityInterceptor.PEER_IDENTITY.get();
        if (peer == null) {
            return refuse(outbound, ChannelType.CONTROL, "anonymous-peer");
        }
        registry.register(peer, outbound);
        ScheduledFuture<?> heartbeat = scheduleHeartbeat(outbound);
        return new StreamObserver<>() {
            private long nextSeq = 0;

            @Override
            public void onNext(Envelope envelope) {
                String defect = controlDefect(envelope, nextSeq, peer);
                if (defect != null) {
                    cancelQuietly(heartbeat);
                    registry.unregister(peer, outbound);
                    sendErrorAndClose(outbound, ChannelType.CONTROL, defect);
                    return;
                }
                nextSeq = envelope.getFrameSeq() + 1;
                dispatchControl(peer, envelope);
            }

            @Override
            public void onError(Throwable t) {
                cancelQuietly(heartbeat);
                registry.unregister(peer, outbound);
            }

            @Override
            public void onCompleted() {
                cancelQuietly(heartbeat);
                registry.unregister(peer, outbound);
                completeQuietly(outbound);
            }
        };
    }

    /** First failure wins; null = accepted. */
    private String controlDefect(Envelope envelope, long expectedSeq, PeerIdentity peer) {
        DecodeResult<Envelope> base = RemoteBridgeProtoAdapter.validateEnvelope(envelope);
        if (!base.isOk()) {
            return base.rejectReason();
        }
        if (envelope.getChannelType() != ChannelType.CONTROL) {
            return "control-wrong-channel";
        }
        // Directional allowlist — inbound is the AGENT side of the tunnel
        switch (envelope.getPayloadCase()) {
            case AGENT_HELLO, CONSENT_RESULT, AUDIT_EVENT, HEARTBEAT, ERROR -> {
            }
            default -> {
                return "control-inbound-payload-refused";
            }
        }
        if (envelope.getFrameSeq() != expectedSeq) {
            return "control-frame-seq";
        }
        if (envelope.getPayloadCase() == Envelope.PayloadCase.AGENT_HELLO) {
            String helloDefect = agentHelloDefect(envelope, peer);
            if (helloDefect != null) {
                return helloDefect;
            }
        }
        return null;
    }

    /** Advisory hello may be ignored, never believed over the certificate (Codex T-2b). */
    private String agentHelloDefect(Envelope envelope, PeerIdentity peer) {
        DecodeResult<RemoteBridgeMessages.AgentHello> hello =
                RemoteBridgeProtoAdapter.decode(envelope.getAgentHello());
        if (!hello.isOk()) {
            return hello.rejectReason();
        }
        String certBound = peer.certBoundDeviceId().orElse(null);
        if (certBound != null && !certBound.equals(hello.orElseThrow().deviceId())) {
            return "agent-hello-device-id-contradicts-certificate";
        }
        return null;
    }

    private void dispatchControl(PeerIdentity peer, Envelope envelope) {
        switch (envelope.getPayloadCase()) {
            case AGENT_HELLO -> RemoteBridgeProtoAdapter.decode(envelope.getAgentHello())
                    .ifOk(hello -> controlPlane.onAgentHello(peer, hello));
            case CONSENT_RESULT -> RemoteBridgeProtoAdapter.decode(envelope.getConsentResult())
                    .ifOk(result -> controlPlane.onConsentResult(peer, result));
            case AUDIT_EVENT -> RemoteBridgeProtoAdapter.decode(envelope.getAuditEvent())
                    .ifOk(event -> controlPlane.onAuditEvent(peer, event));
            default -> {
                // HEARTBEAT / ERROR are liveness + diagnostics — no control-plane action in T-2b
            }
        }
    }

    private ScheduledFuture<?> scheduleHeartbeat(StreamObserver<Envelope> outbound) {
        if (heartbeatScheduler == null || heartbeatIntervalMillis <= 0) {
            return null;
        }
        return heartbeatScheduler.scheduleWithFixedDelay(() -> {
            try {
                outbound.onNext(heartbeatEnvelope());
            } catch (RuntimeException e) {
                // the stream is gone; its observer lifecycle (onError/onCompleted) does the unregistering
            }
        }, heartbeatIntervalMillis, heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private Envelope heartbeatEnvelope() {
        return Envelope.newBuilder()
                .setChannelType(ChannelType.CONTROL)
                .setSentAtEpochMillis(clock.getAsLong())
                .setHeartbeat(Heartbeat.newBuilder()
                        .setHeartbeatIntervalMillis(heartbeatIntervalMillis)
                        .setLeaseExpiresAtEpochMillis(0) // broker lease wiring is T-4
                        .setProtocolVersion(protocolVersion))
                .build();
    }

    // ------------------------------------------------------------------
    // DATA
    // ------------------------------------------------------------------

    @Override
    public StreamObserver<Envelope> data(StreamObserver<Envelope> outbound) {
        PeerIdentity peer = PeerIdentityInterceptor.PEER_IDENTITY.get();
        if (peer == null) {
            return refuse(outbound, ChannelType.DATA, "anonymous-peer");
        }
        return new StreamObserver<>() {
            private final Map<String, Long> nextSeqByStream = new ConcurrentHashMap<>();

            @Override
            public void onNext(Envelope envelope) {
                String defect = dataDefect(envelope, nextSeqByStream);
                if (defect != null) {
                    sendErrorAndClose(outbound, ChannelType.DATA, defect);
                }
                // accepted DATA frames are deliberately inert in T-2b — no business semantics until T-4
            }

            @Override
            public void onError(Throwable t) {
                // client cancelled/failed — nothing registered for DATA in T-2b
            }

            @Override
            public void onCompleted() {
                completeQuietly(outbound);
            }
        };
    }

    /** First failure wins; null = accepted. DATA sequencing authority is DataFrame.frameSeq (Codex T-2b). */
    private String dataDefect(Envelope envelope, Map<String, Long> nextSeqByStream) {
        DecodeResult<Envelope> base = RemoteBridgeProtoAdapter.validateEnvelope(envelope);
        if (!base.isOk()) {
            return base.rejectReason();
        }
        if (envelope.getChannelType() != ChannelType.DATA) {
            return "data-wrong-channel";
        }
        switch (envelope.getPayloadCase()) {
            case DATA_FRAME, HEARTBEAT, ERROR -> {
            }
            default -> {
                return "data-inbound-payload-refused";
            }
        }
        if (envelope.getFrameSeq() != 0) {
            // proto3 int64 has no presence — the envelope counter cannot be "optional", so on DATA it is
            // pinned to 0 and DataFrame.frameSeq is the only sequencing authority
            return "data-envelope-frame-seq-must-be-zero";
        }
        if (envelope.getPayloadCase() != Envelope.PayloadCase.DATA_FRAME) {
            return null; // heartbeat/error carry no frame
        }
        var frame = envelope.getDataFrame();
        if (!envelope.getStreamId().isEmpty() && !envelope.getStreamId().equals(frame.getStreamId())) {
            return "data-stream-id-mismatch";
        }
        if (frame.getPayload().size() > maxDataFrameBytes) {
            return "data-frame-too-large";
        }
        long expected = nextSeqByStream.getOrDefault(frame.getStreamId(), 0L);
        if (frame.getFrameSeq() != expected) {
            return "data-frame-seq";
        }
        nextSeqByStream.put(frame.getStreamId(), expected + 1);
        return null;
    }

    // ------------------------------------------------------------------
    // shared
    // ------------------------------------------------------------------

    private StreamObserver<Envelope> refuse(StreamObserver<Envelope> outbound, ChannelType channel, String code) {
        sendErrorAndClose(outbound, channel, code);
        return new StreamObserver<>() {
            @Override
            public void onNext(Envelope envelope) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        };
    }

    private void sendErrorAndClose(StreamObserver<Envelope> outbound, ChannelType channel, String code) {
        try {
            outbound.onNext(Envelope.newBuilder()
                    .setChannelType(channel)
                    .setSentAtEpochMillis(clock.getAsLong())
                    .setError(ErrorFrame.newBuilder().setCode(code).setRetryable(false))
                    .build());
            outbound.onCompleted();
        } catch (RuntimeException e) {
            // already terminated — closing was the goal
        }
    }

    private static void completeQuietly(StreamObserver<Envelope> outbound) {
        try {
            outbound.onCompleted();
        } catch (RuntimeException ignored) {
            // already terminated
        }
    }

    private static void cancelQuietly(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }
}
