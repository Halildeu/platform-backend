package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame;

/**
 * Faz 22.6 T-2b / #1588 (Codex 019ecbc5) — the seam between the DATA transport layer and the broker's
 * data-plane CONSUMPTION (durable recording / operator fan-out). It is the symmetric counterpart of
 * {@link ControlPlaneHandler}: the T-2b service validates inbound DATA frames (channel, payload allowlist,
 * {@code envelope.frameSeq==0}, per-{@code streamId} {@code DataFrame.frameSeq} sequencing, the byte cap) and
 * hands ACCEPTED frames here together with the AUTHENTICATED {@link PeerIdentity}.
 *
 * <p>Implementations receive the already validated routing context. T-4 durable DATA recording uses
 * {@code sessionId} as the WORM chain id and {@code frame.streamId} as the operation/data-stream id.
 *
 * <p>{@link #INERT} is the default (and only) T-2b implementation: accept-and-drop, so the transport slice
 * stays content-free and behaviour is unchanged. A real handler MUST NOT assume it can throw safely: a throw
 * is metered ({@code remote_access_bridge_data_handler_errors_total}) and closes the DATA stream, but the
 * INERT default never escalates to a session kill — kill-on-recording-failure belongs to the owner-gated
 * recording slice, not the transport.
 */
public interface DataPlaneHandler {

    /** An accepted, validated DATA frame from an authenticated peer (opaque payload; no business decode here). */
    void onDataFrame(PeerIdentity peer, String sessionId, DataFrame frame);

    /** T-2b default: accept-and-drop. Real data-plane consumption (record / fan-out) is owner-gated T-4. */
    DataPlaneHandler INERT = (peer, sessionId, frame) -> {
    };
}
