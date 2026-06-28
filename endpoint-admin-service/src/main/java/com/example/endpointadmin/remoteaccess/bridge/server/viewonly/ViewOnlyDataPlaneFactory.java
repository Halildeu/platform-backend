package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.example.endpointadmin.remoteaccess.bridge.DurableRemoteBridgeAuditSink;
import com.example.endpointadmin.remoteaccess.bridge.server.DataPlaneHandler;
import com.example.endpointadmin.remoteaccess.bridge.server.DurableRecordingDataPlaneHandler;
import com.example.endpointadmin.remoteaccess.bridge.server.RemoteBridgeServerProperties;

import java.util.Objects;

/**
 * Faz 22.6 #1580 (ADR-0044 D3; Codex 019f078a) — selects the VIEW_ONLY DATA-plane handler by recording mode.
 * Extracted from the server config so the selection (and its fail-closed posture) is unit-testable without a
 * Spring context.
 *
 * <ul>
 *   <li>{@code DISABLED} (default) → the {@link LiveOnlyViewDataPlaneHandler} as-is: live fanout, no content
 *       persistence.</li>
 *   <li>{@code ENABLED} → a {@link RecordingThenFanoutDataPlaneHandler} that records to the durable WORM sink
 *       BEFORE fanning out (fail-closed on a recording failure). An enabled bridge with no durable sink is a
 *       misconfiguration and is rejected here.</li>
 *   <li>{@code null} mode → rejected (fail-closed); a real {@code RecordingMode} can only be DISABLED/ENABLED,
 *       and an unknown config string fails the enum binding before this point.</li>
 * </ul>
 */
public final class ViewOnlyDataPlaneFactory {

    private ViewOnlyDataPlaneFactory() {
    }

    public static DataPlaneHandler select(RemoteBridgeServerProperties.ViewOnly viewOnly,
                                          DurableRemoteBridgeAuditSink durableAuditSink,
                                          LiveOnlyViewDataPlaneHandler liveOnlyHandler) {
        Objects.requireNonNull(viewOnly, "viewOnly");
        Objects.requireNonNull(liveOnlyHandler, "liveOnlyHandler");
        RemoteBridgeServerProperties.ViewOnly.RecordingMode mode = viewOnly.recordingMode();
        if (mode == null) {
            throw new IllegalStateException("remote-bridge.view-only.recording-mode is required (fail-closed)");
        }
        return switch (mode) {
            case DISABLED -> liveOnlyHandler;
            case ENABLED -> new RecordingThenFanoutDataPlaneHandler(
                    new DurableRecordingDataPlaneHandler(Objects.requireNonNull(durableAuditSink,
                            "an enabled (recording) bridge requires a durable audit sink")),
                    liveOnlyHandler);
        };
    }
}
