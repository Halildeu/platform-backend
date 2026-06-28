package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.example.endpointadmin.remoteaccess.bridge.DurableRemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.server.DataPlaneHandler;
import com.example.endpointadmin.remoteaccess.bridge.server.RemoteBridgeServerProperties.ViewOnly;
import com.example.endpointadmin.remoteaccess.bridge.server.RemoteBridgeServerProperties.ViewOnly.RecordingMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Faz 22.6 #1580 — recording-mode → data-plane handler selection, fail-closed (Codex 019f078a). */
class ViewOnlyDataPlaneFactoryTest {

    private LiveOnlyViewDataPlaneHandler liveOnly() {
        return new LiveOnlyViewDataPlaneHandler(new ViewOnlyStreamAuthorizationRegistry(),
                new ViewOnlyViewerRegistry(1), ViewOnlyMetadataAuditSink.NOOP, new SimpleMeterRegistry(),
                Set.of("image/png"), () -> 0L);
    }

    // never invoked at construction (RecordingThenFanout only wraps it) — fail loudly if it ever is
    private DurableRemoteBridgeAuditSink durableSink() {
        return new DurableRemoteBridgeAuditSink(sessionId -> {
            throw new IllegalStateException("recorder factory must not be called at construction");
        });
    }

    private static ViewOnly disabled() {
        return new ViewOnly(RecordingMode.DISABLED, 1, 0, null, 0, 0, null, null);
    }

    private static ViewOnly enabled() {
        return new ViewOnly(RecordingMode.ENABLED, 1, 0, null, 30, 30, "days", "owner#1580");
    }

    @Test
    void disabled_selectsLiveOnlyHandlerAsIs() {
        LiveOnlyViewDataPlaneHandler live = liveOnly();
        DataPlaneHandler selected = ViewOnlyDataPlaneFactory.select(disabled(), durableSink(), live);
        assertSame(live, selected); // no recording wrapper in recording-off mode
    }

    @Test
    void enabled_selectsRecordThenFanout() {
        DataPlaneHandler selected = ViewOnlyDataPlaneFactory.select(enabled(), durableSink(), liveOnly());
        assertInstanceOf(RecordingThenFanoutDataPlaneHandler.class, selected);
    }

    @Test
    void enabled_withoutDurableSink_failsClosed() {
        assertThrows(NullPointerException.class,
                () -> ViewOnlyDataPlaneFactory.select(enabled(), null, liveOnly()));
    }

    @Test
    void nullArguments_failClosed() {
        assertThrows(NullPointerException.class,
                () -> ViewOnlyDataPlaneFactory.select(null, durableSink(), liveOnly()));
        assertThrows(NullPointerException.class,
                () -> ViewOnlyDataPlaneFactory.select(disabled(), durableSink(), null));
    }
}
