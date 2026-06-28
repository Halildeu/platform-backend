package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame;
import com.example.endpointadmin.remoteaccess.bridge.server.DataPlaneHandler;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;

import java.util.Objects;

/**
 * Faz 22.6 #1580 (ADR-0044 D3; Codex 019f078a) — the recording-ON (opt-in) VIEW_ONLY data-plane handler:
 * <b>record BEFORE fanout</b>, recording-down → fail-closed.
 *
 * <p>An accepted frame is first handed to the durable {@code recorder} (the existing WORM, metadata-hash audit
 * sink). Only if that succeeds is the frame fanned out live to the operator viewer. A recording failure throws
 * out of {@code recorder} and propagates — the live fanout is NEVER reached, and the DATA stream is closed by
 * the transport layer (the broker would rather lose the live view than emit an unrecorded frame). This is the
 * inverse privacy/compliance posture of {@link LiveOnlyViewDataPlaneHandler}: enabling recording is the
 * parametric-retention opt-in (owner-declared retention + decision ref), and it is fail-closed on the recorder.
 */
public final class RecordingThenFanoutDataPlaneHandler implements DataPlaneHandler {

    private final DataPlaneHandler recorder;
    private final DataPlaneHandler fanout;

    public RecordingThenFanoutDataPlaneHandler(DataPlaneHandler recorder, DataPlaneHandler fanout) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.fanout = Objects.requireNonNull(fanout, "fanout");
    }

    @Override
    public void onDataFrame(PeerIdentity peer, String sessionId, DataFrame frame) {
        // record FIRST — a durable-record failure throws here and STOPS the frame: recording-down is fail-closed,
        // the live fanout below is never reached, and the transport closes the DATA stream.
        recorder.onDataFrame(peer, sessionId, frame);
        // only a successfully recorded frame is fanned out live to the operator viewer
        fanout.onDataFrame(peer, sessionId, frame);
    }
}
