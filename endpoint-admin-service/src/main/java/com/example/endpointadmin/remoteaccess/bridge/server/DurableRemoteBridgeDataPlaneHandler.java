package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.CertThumbprint;
import com.example.endpointadmin.remoteaccess.SessionRecorder;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.RecordKind;
import com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Durable DATA-plane recorder for remote-bridge operation output.
 *
 * <p>It records metadata hashes, not raw terminal bytes. The canonical content hash binds the authenticated
 * transport peer, session id, operation stream id, frame sequence, content type, end marker, and payload hash.
 */
public final class DurableRemoteBridgeDataPlaneHandler implements DataPlaneHandler {

    private static final String HASH_DOMAIN = "RemoteBridgeDataFrame:v1";

    private final Function<String, SessionRecorder> recorderFactory;
    private final Function<String, String> sessionResolver;
    private final LongSupplier clock;

    public DurableRemoteBridgeDataPlaneHandler(Function<String, SessionRecorder> recorderFactory,
                                               LongSupplier clock) {
        this(recorderFactory, streamId -> null, clock);
    }

    public DurableRemoteBridgeDataPlaneHandler(Function<String, SessionRecorder> recorderFactory,
                                               Function<String, String> sessionResolver,
                                               LongSupplier clock) {
        if (recorderFactory == null || clock == null) {
            throw new IllegalArgumentException("recorderFactory and clock are required");
        }
        this.recorderFactory = recorderFactory;
        this.sessionResolver = sessionResolver == null ? streamId -> null : sessionResolver;
        this.clock = clock;
    }

    @Override
    public void onDataFrame(PeerIdentity peer, String sessionId, DataFrame frame) {
        if (peer == null || frame == null) {
            throw new IllegalArgumentException("peer and frame are required");
        }
        String effectiveSessionId = sessionId;
        if (effectiveSessionId == null || effectiveSessionId.isBlank()) {
            effectiveSessionId = sessionResolver.apply(frame.getStreamId());
        }
        if (effectiveSessionId == null || effectiveSessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required for DATA recording");
        }
        String contentHash = contentHash(peer, effectiveSessionId, frame);
        boolean recorded = recorderFactory.apply(effectiveSessionId)
                .record(RecordKind.AGENT_OUTPUT, contentHash, clock.getAsLong());
        if (!recorded) {
            throw new IllegalStateException("remote-bridge DATA frame recording failed");
        }
    }

    static String contentHash(PeerIdentity peer, String sessionId, DataFrame frame) {
        String payloadHash = CertThumbprint.ofDer(frame.getPayload().toByteArray());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeField(out, HASH_DOMAIN);
        writeField(out, peer.transportPeerKey());
        writeField(out, peer.certBoundDeviceId().orElse(""));
        writeField(out, sessionId);
        writeField(out, frame.getStreamId());
        writeField(out, Long.toString(frame.getFrameSeq()));
        writeField(out, frame.getContentType());
        writeField(out, Boolean.toString(frame.getEndStream()));
        writeField(out, payloadHash == null ? CertThumbprint.ofDer(new byte[]{0}) : payloadHash);
        return CertThumbprint.ofDer(out.toByteArray());
    }

    private static void writeField(ByteArrayOutputStream out, String field) {
        byte[] bytes = (field == null ? "" : field).getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length >>> 24) & 0xFF);
        out.write((bytes.length >>> 16) & 0xFF);
        out.write((bytes.length >>> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.writeBytes(bytes);
    }
}
