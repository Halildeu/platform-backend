package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.RecordingAnchorSigner;
import com.example.endpointadmin.remoteaccess.RecordingSink;
import com.example.endpointadmin.remoteaccess.SessionRecorder;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.Entry;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.RecordKind;
import com.example.endpointadmin.remoteaccess.bridge.DurableRemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.AuditEvent;
import com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableRecordingDataPlaneHandlerTest {

    private static final String ALG = "SHA256withECDSA";
    private static final PeerIdentity PEER = new PeerIdentity("peer-1", Optional.of("device-1"), List.of());
    private final KeyPair keyPair = ecKeyPair();

    private static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class CapturingSink implements RecordingSink {
        final List<Entry> entries = new ArrayList<>();

        @Override
        public void append(Entry entry) {
            entries.add(entry);
        }

        @Override
        public boolean isWritable() {
            return true;
        }
    }

    private static final class ThrowingSink implements RecordingSink {
        @Override
        public void append(Entry entry) throws RecordingSinkException {
            throw new RecordingSinkException("store down");
        }

        @Override
        public boolean isWritable() {
            return false;
        }
    }

    private SessionRecorder recorderWith(RecordingSink sink, String sessionId) {
        return new SessionRecorder(sink, new RecordingAnchorSigner(sessionId, keyPair.getPrivate(), ALG));
    }

    @Test
    void recordsAgentOutputOnTheSameSessionChainAsPolicyDecisions() {
        CapturingSink sink = new CapturingSink();
        DurableRemoteBridgeAuditSink audit = new DurableRemoteBridgeAuditSink(id -> recorderWith(sink, id));
        DurableRecordingDataPlaneHandler handler = new DurableRecordingDataPlaneHandler(audit);

        audit.record(new AuditEvent("sess-1", "ALLOW_DECISION:op-1", "a".repeat(64), 1_000L));
        DataFrame frame = frame("op-1", 0, "text/plain", "AgentPc2\r\n".getBytes(), false);
        handler.onDataFrame(PEER, "sess-1", frame);

        assertEquals(2, sink.entries.size());
        assertEquals(RecordKind.POLICY_EVENT, sink.entries.get(0).kind());
        assertEquals(RecordKind.AGENT_OUTPUT, sink.entries.get(1).kind());
        assertEquals(DurableRecordingDataPlaneHandler.frameContentHash("sess-1", frame),
                sink.entries.get(1).contentHash());
        assertEquals(64, sink.entries.get(1).contentHash().length());
    }

    @Test
    void emptyTerminalFramesCreateSessionEndMetadataRecords() {
        CapturingSink sink = new CapturingSink();
        DurableRecordingDataPlaneHandler handler = new DurableRecordingDataPlaneHandler(
                new DurableRemoteBridgeAuditSink(id -> recorderWith(sink, id)));

        DataFrame frame = frame("op-1", 1, "text/plain", new byte[0], true);
        handler.onDataFrame(PEER, "sess-1", frame);

        assertEquals(1, sink.entries.size());
        assertEquals(RecordKind.SESSION_END, sink.entries.get(0).kind());
        assertEquals(DurableRecordingDataPlaneHandler.frameContentHash("sess-1", frame),
                sink.entries.get(0).contentHash());
    }

    @Test
    void emptyNonTerminalFramesDoNotCreateLongRetentionMetadataRecords() {
        CapturingSink sink = new CapturingSink();
        DurableRecordingDataPlaneHandler handler = new DurableRecordingDataPlaneHandler(
                new DurableRemoteBridgeAuditSink(id -> recorderWith(sink, id)));

        handler.onDataFrame(PEER, "sess-1", frame("op-1", 1, "text/plain", new byte[0], false));

        assertTrue(sink.entries.isEmpty());
    }

    @Test
    void payloadFrameWithEndStreamRecordsOutputThenSessionEnd() {
        CapturingSink sink = new CapturingSink();
        DurableRecordingDataPlaneHandler handler = new DurableRecordingDataPlaneHandler(
                new DurableRemoteBridgeAuditSink(id -> recorderWith(sink, id)));

        DataFrame frame = frame("op-1", 0, "text/plain", "AgentPc2\r\n".getBytes(), true);
        handler.onDataFrame(PEER, "sess-1", frame);

        assertEquals(2, sink.entries.size());
        assertEquals(RecordKind.AGENT_OUTPUT, sink.entries.get(0).kind());
        assertEquals(RecordKind.SESSION_END, sink.entries.get(1).kind());
        assertEquals(DurableRecordingDataPlaneHandler.frameContentHash("sess-1", frame),
                sink.entries.get(0).contentHash());
        assertEquals(DurableRecordingDataPlaneHandler.frameContentHash("sess-1", frame),
                sink.entries.get(1).contentHash());
    }

    @Test
    void durableWriteFailureFailsTheDataPlaneClosed() {
        DurableRecordingDataPlaneHandler handler = new DurableRecordingDataPlaneHandler(
                new DurableRemoteBridgeAuditSink(id -> recorderWith(new ThrowingSink(), id)));

        assertThrows(IllegalStateException.class,
                () -> handler.onDataFrame(PEER, "sess-1",
                        frame("op-1", 0, "text/plain", "AgentPc2\r\n".getBytes(), false)));
    }

    private static DataFrame frame(String streamId, long seq, String contentType, byte[] payload,
                                   boolean endStream) {
        return DataFrame.newBuilder()
                .setStreamId(streamId)
                .setFrameSeq(seq)
                .setContentType(contentType)
                .setPayload(ByteString.copyFrom(payload))
                .setEndStream(endStream)
                .build();
    }
}
