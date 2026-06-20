package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.DurableRemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Owner-gated DATA-plane consumer for Faz 22.6 constrained operations.
 *
 * <p>The broker stores only metadata: a SHA-256 hash over the accepted DATA frame
 * envelope fields plus payload bytes. Raw endpoint output remains out of this
 * long-retention WORM chain.
 */
public final class DurableRecordingDataPlaneHandler implements DataPlaneHandler {

    private static final String HASH_DOMAIN = "RemoteBridgeDataFrame:v1";

    private final DurableRemoteBridgeAuditSink auditSink;

    public DurableRecordingDataPlaneHandler(DurableRemoteBridgeAuditSink auditSink) {
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
    }

    @Override
    public void onDataFrame(PeerIdentity peer, String sessionId, DataFrame frame) {
        if (peer == null || sessionId == null || sessionId.isBlank() || frame == null) {
            throw new IllegalArgumentException("peer + sessionId + frame are required");
        }
        if (frame.getPayload().isEmpty()) {
            return;
        }
        auditSink.recordAgentOutput(sessionId, frameContentHash(sessionId, frame), System.currentTimeMillis());
    }

    static String frameContentHash(String sessionId, DataFrame frame) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeField(out, HASH_DOMAIN);
            writeField(out, sessionId);
            writeField(out, frame.getStreamId());
            writeField(out, Long.toString(frame.getFrameSeq()));
            writeField(out, frame.getContentType());
            writeField(out, Boolean.toString(frame.getEndStream()));
            writeBytes(out, frame.getPayload().toByteArray());
            return HexFormat.of().formatHex(digest.digest(out.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void writeField(ByteArrayOutputStream out, String field) {
        writeBytes(out, field.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        out.write((bytes.length >>> 24) & 0xFF);
        out.write((bytes.length >>> 16) & 0xFF);
        out.write((bytes.length >>> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.writeBytes(bytes);
    }
}
