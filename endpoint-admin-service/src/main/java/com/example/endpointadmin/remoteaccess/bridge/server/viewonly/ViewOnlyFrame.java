package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.google.protobuf.ByteString;

import java.util.Objects;

/**
 * Faz 22.6 #1580 (Codex 019f078a) — a single live VIEW_ONLY screen frame on its way from an authenticated
 * agent DATA stream to a registered operator viewer. It is a <b>live, in-memory, drop-tolerant</b> value: the
 * {@link com.example.endpointadmin.remoteaccess.bridge.server.viewonly.LiveOnlyViewDataPlaneHandler} builds one
 * per accepted frame, the {@link ViewOnlyViewerRegistry} hands it to the (at most one) subscribed viewer with
 * latest-wins backpressure, and it is dropped. It is NEVER written to any durable / WORM / recording store —
 * the recording-off privacy guarantee (ADR-0044 D3) is that no type on this path can persist {@link #payload}.
 *
 * <p>{@code payload} is a protobuf {@link ByteString} (immutable, zero-copy from the wire frame) so fanning a
 * frame out never copies the screen bytes and a viewer can never mutate them.
 */
public record ViewOnlyFrame(String sessionId,
                            String streamId,
                            long frameSeq,
                            String contentType,
                            ByteString payload,
                            boolean endStream,
                            long observedAtEpochMillis) {

    public ViewOnlyFrame {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (streamId == null || streamId.isBlank()) {
            throw new IllegalArgumentException("streamId is required");
        }
        payload = payload == null ? ByteString.EMPTY : payload;
        contentType = contentType == null ? "" : contentType;
    }

    /** Frame payload size in bytes — the only payload fact metadata audit / metrics may observe. */
    public int payloadBytes() {
        return payload.size();
    }

    /** Same routing identity, fresh payload — defensive for tests that need a distinct frame per seq. */
    public ViewOnlyFrame withPayload(ByteString newPayload, long frameSeq) {
        return new ViewOnlyFrame(sessionId, streamId, frameSeq, contentType,
                Objects.requireNonNull(newPayload, "newPayload"), endStream, observedAtEpochMillis);
    }
}
