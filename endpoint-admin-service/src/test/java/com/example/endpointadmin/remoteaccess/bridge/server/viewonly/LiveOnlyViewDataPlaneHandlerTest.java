package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.example.endpointadmin.remoteaccess.RemoteAccessMetrics;
import com.example.endpointadmin.remoteaccess.bridge.proto.DataFrame;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyStreamAuthorizationRegistry.Authorization;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 #1580 — recording-OFF data-plane handler: gated live fanout + metadata audit, no persistence. */
class LiveOnlyViewDataPlaneHandlerTest {

    private static final PeerIdentity PEER = new PeerIdentity("peer-A", Optional.of("device-1"), List.of());
    private static final long NOW = 1_000L;
    private static final long EXPIRY = 60_000L;

    private SimpleMeterRegistry meters;
    private ViewOnlyStreamAuthorizationRegistry authz;
    private ViewOnlyViewerRegistry viewers;
    private CapturingAuditSink audit;
    private LiveOnlyViewDataPlaneHandler handler;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        authz = new ViewOnlyStreamAuthorizationRegistry();
        viewers = new ViewOnlyViewerRegistry(1);
        audit = new CapturingAuditSink();
        handler = new LiveOnlyViewDataPlaneHandler(authz, viewers, audit, meters,
                Set.of("image/png", "image/jpeg"), () -> NOW);
    }

    private static DataFrame frame(String streamId, String contentType) {
        return DataFrame.newBuilder()
                .setStreamId(streamId)
                .setFrameSeq(0)
                .setContentType(contentType)
                .setPayload(ByteString.copyFromUtf8("pixels"))
                .setEndStream(false)
                .build();
    }

    private static final Object INCARNATION = new Object();

    private void authorize(String streamId) {
        authz.beginSession("s1", INCARNATION);
        authz.authorize(INCARNATION,
                new Authorization("s1", streamId, "peer-A", "operator@acik", "device-1", EXPIRY));
    }

    private double frames(String disposition) {
        return meters.counter(RemoteAccessMetrics.VIEW_ONLY_FANOUT_FRAMES, "disposition", disposition).count();
    }

    @Test
    void authorizedAllowedFrame_withViewer_isDelivered() {
        authorize("op-1");
        ViewOnlyViewerSubscription viewer = viewers.subscribe("s1", null).orElseThrow();

        handler.onDataFrame(PEER, "s1", frame("op-1", "image/png"));

        Optional<ViewOnlyFrame> got = viewer.poll();
        assertTrue(got.isPresent());
        assertEquals("op-1", got.get().streamId());
        assertEquals(ViewOnlyMetadataAuditSink.Disposition.DELIVERED, audit.lastDisposition.get());
        assertEquals(6, audit.lastPayloadBytes.get()); // "pixels" = 6 bytes (size, never the bytes)
        assertEquals(1.0, frames("delivered"));
        assertEquals(6.0, meters.counter(RemoteAccessMetrics.VIEW_ONLY_FANOUT_BYTES).count());
    }

    @Test
    void authorizedAllowedFrame_noViewer_isDroppedNoViewer() {
        authorize("op-1"); // authorized, but nobody subscribed

        handler.onDataFrame(PEER, "s1", frame("op-1", "image/png"));

        assertEquals(ViewOnlyMetadataAuditSink.Disposition.DROPPED_NO_VIEWER, audit.lastDisposition.get());
        assertEquals(1.0, frames("dropped-no-viewer"));
        assertEquals(0.0, meters.counter(RemoteAccessMetrics.VIEW_ONLY_FANOUT_BYTES).count());
    }

    @Test
    void unauthorizedStream_isDroppedFailClosed() {
        ViewOnlyViewerSubscription viewer = viewers.subscribe("s1", null).orElseThrow();
        // no authorization recorded for op-1

        handler.onDataFrame(PEER, "s1", frame("op-1", "image/png"));

        assertTrue(viewer.poll().isEmpty()); // nothing fanned out
        assertEquals(ViewOnlyMetadataAuditSink.Disposition.UNAUTHORIZED, audit.lastDisposition.get());
        assertEquals(1.0, frames("unauthorized"));
    }

    @Test
    void authorizationBoundToDifferentPeer_isUnauthorized() {
        authz.beginSession("s1", INCARNATION);
        authz.authorize(INCARNATION, new Authorization("s1", "op-1", "peer-OTHER", "operator@acik", "device-1", EXPIRY));
        viewers.subscribe("s1", null).orElseThrow();

        handler.onDataFrame(PEER, "s1", frame("op-1", "image/png")); // PEER is peer-A, grant is peer-OTHER

        assertEquals(ViewOnlyMetadataAuditSink.Disposition.UNAUTHORIZED, audit.lastDisposition.get());
        assertEquals(1.0, frames("unauthorized"));
    }

    @Test
    void disallowedContentType_isMimeRejected() {
        authorize("op-1");
        ViewOnlyViewerSubscription viewer = viewers.subscribe("s1", null).orElseThrow();

        handler.onDataFrame(PEER, "s1", frame("op-1", "text/plain"));

        assertTrue(viewer.poll().isEmpty()); // a non-image frame is never fanned out
        assertEquals(ViewOnlyMetadataAuditSink.Disposition.MIME_REJECTED, audit.lastDisposition.get());
        assertEquals(1.0, frames("mime-rejected"));
    }

    @Test
    void contentTypeMatch_isCaseInsensitive() {
        authorize("op-1");
        ViewOnlyViewerSubscription viewer = viewers.subscribe("s1", null).orElseThrow();

        handler.onDataFrame(PEER, "s1", frame("op-1", "IMAGE/PNG"));

        assertTrue(viewer.poll().isPresent());
        assertEquals(ViewOnlyMetadataAuditSink.Disposition.DELIVERED, audit.lastDisposition.get());
    }

    @Test
    void nullArguments_throw() {
        assertThrows(IllegalArgumentException.class, () -> handler.onDataFrame(null, "s1", frame("op-1", "image/png")));
        assertThrows(IllegalArgumentException.class, () -> handler.onDataFrame(PEER, null, frame("op-1", "image/png")));
        assertThrows(IllegalArgumentException.class, () -> handler.onDataFrame(PEER, " ", frame("op-1", "image/png")));
        assertThrows(IllegalArgumentException.class, () -> handler.onDataFrame(PEER, "s1", null));
    }

    @Test
    void blankStreamId_isUnauthorized() {
        viewers.subscribe("s1", null).orElseThrow();
        handler.onDataFrame(PEER, "s1", frame("", "image/png"));
        assertEquals(ViewOnlyMetadataAuditSink.Disposition.UNAUTHORIZED, audit.lastDisposition.get());
    }

    private static final class CapturingAuditSink implements ViewOnlyMetadataAuditSink {
        final AtomicReference<Disposition> lastDisposition = new AtomicReference<>();
        final AtomicReference<Integer> lastPayloadBytes = new AtomicReference<>();

        @Override
        public void onFrameObserved(String sessionId, String streamId, long frameSeq, int payloadBytes,
                                    String contentType, Disposition disposition, long epochMillis) {
            lastDisposition.set(disposition);
            lastPayloadBytes.set(payloadBytes);
        }
    }
}
