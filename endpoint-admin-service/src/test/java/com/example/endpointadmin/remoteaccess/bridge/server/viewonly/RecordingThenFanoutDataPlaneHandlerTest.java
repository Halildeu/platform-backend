package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame;
import com.example.endpointadmin.remoteaccess.bridge.server.DataPlaneHandler;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Faz 22.6 #1580 — enabled-mode handler: record BEFORE fanout, recording-down → fail-closed (Codex 019f078a). */
class RecordingThenFanoutDataPlaneHandlerTest {

    private static final PeerIdentity PEER = new PeerIdentity("peer-A", Optional.of("device-1"), List.of());

    private static DataFrame frame() {
        return DataFrame.newBuilder().setStreamId("op-1").setFrameSeq(0).setContentType("image/png")
                .setPayload(ByteString.copyFromUtf8("px")).build();
    }

    @Test
    void recordsBeforeFanout() {
        List<String> order = new ArrayList<>();
        DataPlaneHandler recorder = (peer, sessionId, frame) -> order.add("record");
        DataPlaneHandler fanout = (peer, sessionId, frame) -> order.add("fanout");

        new RecordingThenFanoutDataPlaneHandler(recorder, fanout).onDataFrame(PEER, "s1", frame());

        assertEquals(List.of("record", "fanout"), order); // record strictly precedes fanout
    }

    @Test
    void recordingFailure_isFailClosed_noFanout() {
        List<String> order = new ArrayList<>();
        DataPlaneHandler recorder = (peer, sessionId, frame) -> {
            order.add("record");
            throw new IllegalStateException("durable WORM write failed");
        };
        DataPlaneHandler fanout = (peer, sessionId, frame) -> order.add("fanout");

        RecordingThenFanoutDataPlaneHandler handler = new RecordingThenFanoutDataPlaneHandler(recorder, fanout);

        // the recorder throw propagates (transport closes the DATA stream) and the live fanout is NEVER reached
        assertThrows(IllegalStateException.class, () -> handler.onDataFrame(PEER, "s1", frame()));
        assertEquals(List.of("record"), order);
    }

    @Test
    void requiresBothCollaborators() {
        DataPlaneHandler noop = (peer, sessionId, frame) -> {
        };
        assertThrows(NullPointerException.class, () -> new RecordingThenFanoutDataPlaneHandler(null, noop));
        assertThrows(NullPointerException.class, () -> new RecordingThenFanoutDataPlaneHandler(noop, null));
    }
}
