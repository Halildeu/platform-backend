package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

/**
 * Faz 22.6 #1580 (ADR-0044 D3/D4; Codex 019f078a) — the metadata-only audit seam for VIEW_ONLY screen
 * observation. Metadata audit is ALWAYS on (independent of recording mode): every observed frame produces one
 * audit call carrying ONLY routing identity + size + disposition — NEVER the payload.
 *
 * <p>The interface is the no-leak guarantee: there is structurally no parameter that can carry frame bytes
 * ({@code byte[]} / {@code ByteString} / {@code ViewOnlyFrame}). A test asserts this by reflection, so a future
 * change that tries to pass content through the audit sink fails the build (ADR-0044 "no content persistence").
 */
public interface ViewOnlyMetadataAuditSink {

    /** What happened to an observed VIEW_ONLY frame — a bounded, fixed set (also the metric {@code disposition} tag). */
    enum Disposition {
        /** Fanned out to at least one live operator viewer. */
        DELIVERED,
        /** Authorized + allowed, but no viewer is currently subscribed — dropped (no persistence). */
        DROPPED_NO_VIEWER,
        /** No active VIEW_ONLY stream authorization for (session, stream, peer) — dropped, fail-closed. */
        UNAUTHORIZED,
        /** The frame content type is not on the VIEW_ONLY image allowlist — dropped. */
        MIME_REJECTED
    }

    /**
     * Record one observed VIEW_ONLY frame as metadata only.
     *
     * @param sessionId    the remote-support session id
     * @param streamId     the DATA stream id (== the SCREEN_VIEW operation id)
     * @param frameSeq     the per-stream frame sequence number
     * @param payloadBytes the frame payload SIZE in bytes — never the bytes themselves
     * @param contentType  the declared frame content type (an allowlist token, not content)
     * @param disposition  what the broker did with the frame
     * @param epochMillis  observation time
     */
    void onFrameObserved(String sessionId,
                         String streamId,
                         long frameSeq,
                         int payloadBytes,
                         String contentType,
                         Disposition disposition,
                         long epochMillis);

    /** A sink that records nothing — used only where audit is deliberately not wired (never in an enabled bridge). */
    ViewOnlyMetadataAuditSink NOOP =
            (sessionId, streamId, frameSeq, payloadBytes, contentType, disposition, epochMillis) -> {
            };
}
