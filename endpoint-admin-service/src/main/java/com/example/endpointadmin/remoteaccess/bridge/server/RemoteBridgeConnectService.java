package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.proto.ChannelType;
import com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame;
import com.example.endpointadmin.remoteaccess.bridge.proto.Envelope;
import com.example.endpointadmin.remoteaccess.bridge.proto.ErrorFrame;
import com.example.endpointadmin.remoteaccess.bridge.proto.Heartbeat;
import com.example.endpointadmin.remoteaccess.bridge.proto.RemoteBridgeGrpc;
import com.example.endpointadmin.remoteaccess.bridge.wire.DecodeResult;
import com.example.endpointadmin.remoteaccess.bridge.wire.RemoteBridgeProtoAdapter;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.BRIDGE_DATA_BYTES;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.BRIDGE_DATA_DEFECTS;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.BRIDGE_DATA_FRAMES;
import static com.example.endpointadmin.remoteaccess.RemoteAccessMetrics.BRIDGE_DATA_HANDLER_ERRORS;

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
    private final DataPlaneHandler dataPlane;
    private final MeterRegistry meters;
    private final ScheduledExecutorService heartbeatScheduler;
    private final long heartbeatIntervalMillis;
    private final int maxDataFrameBytes;
    private final LongSupplier clock;
    private final String protocolVersion;

    public RemoteBridgeConnectService(ControlStreamRegistry registry,
                                      ControlPlaneHandler controlPlane,
                                      DataPlaneHandler dataPlane,
                                      MeterRegistry meters,
                                      ScheduledExecutorService heartbeatScheduler,
                                      long heartbeatIntervalMillis,
                                      int maxDataFrameBytes,
                                      LongSupplier clock,
                                      String protocolVersion) {
        if (registry == null || controlPlane == null || clock == null) {
            throw new IllegalArgumentException("registry, controlPlane and clock are required");
        }
        if (meters == null) {
            throw new IllegalArgumentException("meters is required");
        }
        if (maxDataFrameBytes <= 0) {
            throw new IllegalArgumentException("maxDataFrameBytes must be positive");
        }
        this.registry = registry;
        this.controlPlane = controlPlane;
        // a null data-plane handler means "accept-and-drop" — the T-2b default (no consumption yet)
        this.dataPlane = dataPlane == null ? DataPlaneHandler.INERT : dataPlane;
        this.meters = meters;
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
        // every outbound CONTROL write (error frames, heartbeats, kill, replace-close) is serialized through
        // the handle — a StreamObserver is not a thread-safe sink (Codex P2); closing the handle cancels ITS
        // heartbeat task, so a replaced/killed stream's timer can never keep writing to a completed observer
        ControlStreamHandle handle = new ControlStreamHandle(outbound);
        registry.register(peer, handle);
        // whichever path closes the handle (replace, kill, error, or a heartbeat write dying on a broken
        // stream) also vacates the registry slot — no stale slot until gRPC's inbound cancellation arrives
        handle.attachOnClose(() -> registry.unregister(peer, handle));
        handle.attachHeartbeat(scheduleHeartbeat(handle));
        return new StreamObserver<>() {
            private long nextSeq = 0;

            @Override
            public void onNext(Envelope envelope) {
                String defect = controlDefect(envelope, nextSeq, peer);
                if (defect != null) {
                    registry.unregister(peer, handle);
                    handle.sendAndClose(errorEnvelope(ChannelType.CONTROL, defect));
                    return;
                }
                nextSeq = envelope.getFrameSeq() + 1;
                dispatchControl(peer, envelope);
            }

            @Override
            public void onError(Throwable t) {
                registry.unregister(peer, handle);
                handle.close();
            }

            @Override
            public void onCompleted() {
                registry.unregister(peer, handle);
                handle.close();
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
        // every direction-allowed payload with a domain decoder must decode VALID here — a malformed-but-
        // allowed payload must close the stream, never silently advance the sequence (Codex P1)
        return switch (envelope.getPayloadCase()) {
            case AGENT_HELLO -> agentHelloDefect(envelope, peer);
            case CONSENT_RESULT -> RemoteBridgeProtoAdapter.decode(envelope.getConsentResult()).rejectReason();
            case AUDIT_EVENT -> RemoteBridgeProtoAdapter.decode(envelope.getAuditEvent()).rejectReason();
            default -> null; // HEARTBEAT/ERROR content already validated by validateEnvelope
        };
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
            case HEARTBEAT -> controlPlane.onHeartbeat(peer);
            case ERROR -> RemoteBridgeProtoAdapter.decode(envelope.getSessionId(), envelope.getError())
                    .ifOk(error -> controlPlane.onAgentErrorFrame(peer, error));
            default -> {
                // Directional allowlist already refused broker-originated control payloads.
            }
        }
    }

    private ScheduledFuture<?> scheduleHeartbeat(ControlStreamHandle handle) {
        if (heartbeatScheduler == null || heartbeatIntervalMillis <= 0) {
            return null;
        }
        return heartbeatScheduler.scheduleWithFixedDelay(() -> handle.send(heartbeatEnvelope()),
                heartbeatIntervalMillis, heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
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
                    meters.counter(BRIDGE_DATA_DEFECTS, "reason", defectCategory(defect)).increment();
                    sendErrorAndClose(outbound, ChannelType.DATA, defect);
                    return;
                }
                // accepted DATA_FRAMEs are metered + handed to the data-plane seam (INERT in T-2b = drop;
                // durable recording / operator fan-out are the owner-gated T-4 consumer). heartbeat/error
                // carry no frame, so they are accepted but not dispatched.
                if (envelope.getPayloadCase() == Envelope.PayloadCase.DATA_FRAME) {
                    DataFrame frame = envelope.getDataFrame();
                    meters.counter(BRIDGE_DATA_FRAMES).increment();
                    meters.counter(BRIDGE_DATA_BYTES).increment(frame.getPayload().size());
                    try {
                        dataPlane.onDataFrame(peer, envelope.getSessionId(), frame);
                    } catch (RuntimeException e) {
                        // a consumer fault closes the DATA stream (transport-level) — NEVER a session kill
                        // from the transport (kill-on-recording-failure is the owner-gated recording slice)
                        meters.counter(BRIDGE_DATA_HANDLER_ERRORS).increment();
                        sendErrorAndClose(outbound, ChannelType.DATA, "data-handler-error");
                    }
                }
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

    /**
     * Coarse, BOUNDED-cardinality category for the {@code BRIDGE_DATA_DEFECTS} {@code reason} tag — never the
     * raw defect string (a metric tag must not carry unbounded values). Output set is fixed:
     * seq / too-large / channel / payload / stream-id / envelope / other.
     */
    private static String defectCategory(String defect) {
        if (defect == null) {
            return "other";
        }
        return switch (defect) {
            case "data-frame-seq", "data-envelope-frame-seq-must-be-zero" -> "seq";
            case "data-frame-too-large" -> "too-large";
            case "data-wrong-channel" -> "channel";
            case "data-inbound-payload-refused" -> "payload";
            case "data-stream-id-mismatch" -> "stream-id";
            // every other defect comes from RemoteBridgeProtoAdapter.validateEnvelope() (a bounded code set)
            default -> "envelope";
        };
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

    private Envelope errorEnvelope(ChannelType channel, String code) {
        return Envelope.newBuilder()
                .setChannelType(channel)
                .setSentAtEpochMillis(clock.getAsLong())
                .setError(ErrorFrame.newBuilder().setCode(code).setRetryable(false))
                .build();
    }

    /** DATA + anonymous paths have a SINGLE writer (the inbound handler) — no handle needed there. */
    private void sendErrorAndClose(StreamObserver<Envelope> outbound, ChannelType channel, String code) {
        try {
            outbound.onNext(errorEnvelope(channel, code));
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
}
