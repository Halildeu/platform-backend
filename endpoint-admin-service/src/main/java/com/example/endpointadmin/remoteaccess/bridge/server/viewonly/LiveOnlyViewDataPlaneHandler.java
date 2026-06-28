package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.example.endpointadmin.remoteaccess.RemoteAccessMetrics;
import com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame;
import com.example.endpointadmin.remoteaccess.bridge.server.DataPlaneHandler;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Faz 22.6 #1580 (ADR-0044 D3; Codex 019f078a) — the recording-OFF (default) VIEW_ONLY data-plane handler.
 *
 * <p>It does exactly three things with an accepted screen frame: enforce the fanout gate, fan it out LIVE to the
 * (at most one) subscribed operator viewer, and record metadata. It is the machine-checkable embodiment of
 * "no content persistence": this class has <b>zero compile-time dependency on any durable / WORM / recording
 * sink</b> ({@code DurableRemoteBridgeAuditSink}, {@code SessionRecorder}, {@code DbRecordingSink}). There is no
 * field, constructor parameter, or import through which a screen byte could reach a store — so a recording-off
 * bridge structurally cannot persist content, and a test asserts this by reflection (ADR-0044 D5 negative proof).
 *
 * <p><b>Fanout gate (all required, fail-closed):</b> a live VIEW_ONLY stream authorization for
 * {@code (sessionId, streamId)} bound to the authenticated transport peer and unexpired
 * ({@link ViewOnlyStreamAuthorizationRegistry}), AND a frame content type on the image allowlist. A frame that
 * fails the gate is DROPPED (metered + metadata-audited), never fanned out — and never kills the DATA stream
 * (a transport kill on a benign mid-session permit-expiry race would be wrong; the metric is the alarm).
 */
public final class LiveOnlyViewDataPlaneHandler implements DataPlaneHandler {

    private final ViewOnlyStreamAuthorizationRegistry authorization;
    private final ViewOnlyViewerRegistry viewers;
    private final ViewOnlyMetadataAuditSink audit;
    private final MeterRegistry meters;
    private final Set<String> allowedContentTypes;
    private final LongSupplier clock;

    public LiveOnlyViewDataPlaneHandler(ViewOnlyStreamAuthorizationRegistry authorization,
                                        ViewOnlyViewerRegistry viewers,
                                        ViewOnlyMetadataAuditSink audit,
                                        MeterRegistry meters,
                                        Set<String> allowedContentTypes,
                                        LongSupplier clock) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.viewers = Objects.requireNonNull(viewers, "viewers");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.meters = Objects.requireNonNull(meters, "meters");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(allowedContentTypes, "allowedContentTypes");
        this.allowedContentTypes = Set.copyOf(allowedContentTypes.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.strip().toLowerCase(Locale.ROOT))
                .toList());
        if (this.allowedContentTypes.isEmpty()) {
            throw new IllegalArgumentException("allowedContentTypes must be non-empty");
        }
    }

    @Override
    public void onDataFrame(PeerIdentity peer, String sessionId, DataFrame frame) {
        if (peer == null || sessionId == null || sessionId.isBlank() || frame == null) {
            throw new IllegalArgumentException("peer + sessionId + frame are required");
        }
        long now = clock.getAsLong();
        String streamId = frame.getStreamId();
        String contentType = frame.getContentType() == null ? "" : frame.getContentType();
        int payloadBytes = frame.getPayload().size();

        // gate 1 — VIEW_ONLY stream authorization bound to THIS authenticated peer, unexpired (fail-closed)
        if (streamId == null || streamId.isBlank()
                || !authorization.isAuthorized(sessionId, streamId, peer.transportPeerKey(), now)) {
            drop(sessionId, streamId, frame.getFrameSeq(), payloadBytes, contentType,
                    ViewOnlyMetadataAuditSink.Disposition.UNAUTHORIZED, now);
            return;
        }
        // gate 2 — image MIME allowlist
        if (!allowedContentTypes.contains(contentType.strip().toLowerCase(Locale.ROOT))) {
            drop(sessionId, streamId, frame.getFrameSeq(), payloadBytes, contentType,
                    ViewOnlyMetadataAuditSink.Disposition.MIME_REJECTED, now);
            return;
        }

        // live fanout — latest-wins, dropped if no viewer (no buffer, no store)
        ViewOnlyFrame viewFrame = new ViewOnlyFrame(sessionId, streamId, frame.getFrameSeq(),
                contentType, frame.getPayload(), frame.getEndStream(), now);
        int delivered = viewers.publish(viewFrame);
        if (delivered > 0) {
            meters.counter(RemoteAccessMetrics.VIEW_ONLY_FANOUT_FRAMES, "disposition", "delivered").increment();
            meters.counter(RemoteAccessMetrics.VIEW_ONLY_FANOUT_BYTES).increment(payloadBytes);
            audit.onFrameObserved(sessionId, streamId, frame.getFrameSeq(), payloadBytes, contentType,
                    ViewOnlyMetadataAuditSink.Disposition.DELIVERED, now);
        } else {
            drop(sessionId, streamId, frame.getFrameSeq(), payloadBytes, contentType,
                    ViewOnlyMetadataAuditSink.Disposition.DROPPED_NO_VIEWER, now);
        }
    }

    private void drop(String sessionId, String streamId, long frameSeq, int payloadBytes, String contentType,
                      ViewOnlyMetadataAuditSink.Disposition disposition, long now) {
        meters.counter(RemoteAccessMetrics.VIEW_ONLY_FANOUT_FRAMES, "disposition", tag(disposition)).increment();
        audit.onFrameObserved(sessionId, streamId == null ? "" : streamId, frameSeq, payloadBytes, contentType,
                disposition, now);
    }

    private static String tag(ViewOnlyMetadataAuditSink.Disposition disposition) {
        return switch (disposition) {
            case DELIVERED -> "delivered";
            case DROPPED_NO_VIEWER -> "dropped-no-viewer";
            case UNAUTHORIZED -> "unauthorized";
            case MIME_REJECTED -> "mime-rejected";
        };
    }
}
