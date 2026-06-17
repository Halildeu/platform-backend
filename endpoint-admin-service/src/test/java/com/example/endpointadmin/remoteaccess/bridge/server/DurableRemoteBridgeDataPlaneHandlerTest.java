package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.RecordingAnchorSigner;
import com.example.endpointadmin.remoteaccess.RecordingSink;
import com.example.endpointadmin.remoteaccess.SessionRecorder;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.Entry;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.RecordKind;
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

class DurableRemoteBridgeDataPlaneHandlerTest {

    private static final PeerIdentity PEER =
            new PeerIdentity("peer-fp-1", Optional.of("dev-1"), List.of());

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

    @Test
    void recordsAcceptedDataFrameAsAgentOutputOnTheSharedSessionChain() {
        CapturingSink sink = new CapturingSink();
        SessionRecorder recorder = new SessionRecorder(sink,
                new RecordingAnchorSigner("sess-1", ecKeyPair().getPrivate(), "SHA256withECDSA"));
        assertTrue(recorder.record(RecordKind.POLICY_EVENT, "a".repeat(64), 1_000L));

        DurableRemoteBridgeDataPlaneHandler handler =
                new DurableRemoteBridgeDataPlaneHandler(id -> recorder, () -> 2_000L);
        handler.onDataFrame(PEER, "sess-1", DataFrame.newBuilder()
                .setStreamId("op-1")
                .setFrameSeq(0)
                .setContentType("application/x-conpty-stream")
                .setPayload(ByteString.copyFromUtf8("SRB-AIDENETIMPC\r\n"))
                .build());

        assertEquals(2, sink.entries.size());
        assertEquals(0, sink.entries.get(0).seq());
        assertEquals(1, sink.entries.get(1).seq());
        assertEquals(RecordKind.AGENT_OUTPUT, sink.entries.get(1).kind());
        assertEquals(2_000L, sink.entries.get(1).timestampMillis());
        assertTrue(sink.entries.get(1).contentHash().matches("[0-9a-f]{64}"));
    }

    @Test
    void resolvesBlankEnvelopeSessionIdFromOperationStreamIdForLegacyAgents() {
        CapturingSink sink = new CapturingSink();
        SessionRecorder recorder = new SessionRecorder(sink,
                new RecordingAnchorSigner("sess-1", ecKeyPair().getPrivate(), "SHA256withECDSA"));
        assertTrue(recorder.record(RecordKind.POLICY_EVENT, "b".repeat(64), 1_000L));

        RemoteBridgeOperationStreamRegistry streams = new RemoteBridgeOperationStreamRegistry();
        streams.bind("op-1", "sess-1");

        DurableRemoteBridgeDataPlaneHandler handler = new DurableRemoteBridgeDataPlaneHandler(
                id -> recorder, streamId -> streams.sessionFor(streamId).orElse(null), () -> 2_000L);
        handler.onDataFrame(PEER, "", DataFrame.newBuilder()
                .setStreamId("op-1")
                .setFrameSeq(0)
                .setContentType("application/x-conpty-stream")
                .setPayload(ByteString.copyFromUtf8("SRB-AIDENETIMPC\r\n"))
                .build());

        assertEquals(2, sink.entries.size());
        assertEquals(RecordKind.AGENT_OUTPUT, sink.entries.get(1).kind());
        assertEquals(1, sink.entries.get(1).seq());
        assertTrue(sink.entries.get(1).contentHash().matches("[0-9a-f]{64}"));
    }

    @Test
    void blankSessionIdFailsClosedBeforeRecording() {
        DurableRemoteBridgeDataPlaneHandler handler =
                new DurableRemoteBridgeDataPlaneHandler(id -> {
                    throw new AssertionError("recorder must not be requested");
                }, () -> 1L);

        assertThrows(IllegalArgumentException.class, () -> handler.onDataFrame(PEER, "",
                DataFrame.newBuilder().setStreamId("op-1").setFrameSeq(0)
                        .setContentType("application/x-conpty-stream").build()));
    }

    private static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
