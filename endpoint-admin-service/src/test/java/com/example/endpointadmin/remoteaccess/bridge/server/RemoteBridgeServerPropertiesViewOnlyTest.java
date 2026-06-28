package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.server.RemoteBridgeServerProperties.ViewOnly;
import com.example.endpointadmin.remoteaccess.bridge.server.RemoteBridgeServerProperties.ViewOnly.RecordingMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 #1580 (ADR-0044 D3/D5) — recording-off default + fail-closed enabled/disabled field validation. */
class RemoteBridgeServerPropertiesViewOnlyTest {

    @Test
    void topLevelDefault_isRecordingOff() {
        RemoteBridgeServerProperties props = new RemoteBridgeServerProperties(
                false, null, 0, 0, 0, 0, null, false, null, null);
        ViewOnly viewOnly = props.viewOnly();

        assertEquals(RecordingMode.DISABLED, viewOnly.recordingMode());
        assertFalse(viewOnly.recordingEnabled());
        assertEquals(1, viewOnly.maxViewersPerSession());
        assertEquals(0, viewOnly.streamAuthorizationTtlMillis());
        assertEquals(List.of("image/png", "image/jpeg", "image/webp"), viewOnly.allowedFrameContentTypes());
    }

    @Test
    void disabled_mustNotCarryEnabledOnlyFields() {
        // retention days on a recording-off bridge → untested-privacy-claim guard (ADR-0044 D5)
        assertThrows(IllegalStateException.class,
                () -> new ViewOnly(RecordingMode.DISABLED, 1, 0, null, 5, 0, null, null));
        assertThrows(IllegalStateException.class,
                () -> new ViewOnly(RecordingMode.DISABLED, 1, 0, null, 0, 5, null, null));
        assertThrows(IllegalStateException.class,
                () -> new ViewOnly(RecordingMode.DISABLED, 1, 0, null, 0, 0, null, "owner#1580"));
    }

    @Test
    void enabled_requiresRetentionAndOwnerRef() {
        assertThrows(IllegalStateException.class, // no retention
                () -> new ViewOnly(RecordingMode.ENABLED, 1, 0, null, 0, 0, "days", "owner#1580"));
        assertThrows(IllegalStateException.class, // no session metadata retention
                () -> new ViewOnly(RecordingMode.ENABLED, 1, 0, null, 30, 0, "days", "owner#1580"));
        assertThrows(IllegalStateException.class, // no owner decision ref
                () -> new ViewOnly(RecordingMode.ENABLED, 1, 0, null, 30, 30, "days", "  "));
    }

    @Test
    void enabled_unitMustBeDays() {
        assertThrows(IllegalStateException.class,
                () -> new ViewOnly(RecordingMode.ENABLED, 1, 0, null, 30, 30, "hours", "owner#1580"));
    }

    @Test
    void enabled_validConfig_isAccepted() {
        ViewOnly viewOnly = new ViewOnly(RecordingMode.ENABLED, 2, 5_000, null, 30, 90, "days", "owner#1580");

        assertTrue(viewOnly.recordingEnabled());
        assertEquals(2, viewOnly.maxViewersPerSession());
        assertEquals(5_000, viewOnly.streamAuthorizationTtlMillis());
        assertEquals(30, viewOnly.recordingRetentionDays());
        assertEquals(90, viewOnly.sessionMetadataRetentionDays());
        assertEquals("days", viewOnly.recordingRetentionUnit());
        assertEquals("owner#1580", viewOnly.ownerDecisionRef());
    }

    @Test
    void negativeRetention_isRejected() {
        assertThrows(IllegalStateException.class,
                () -> new ViewOnly(RecordingMode.ENABLED, 1, 0, null, -1, 30, "days", "owner#1580"));
    }

    @Test
    void contentTypes_areNormalizedAndDeduplicated() {
        ViewOnly viewOnly = new ViewOnly(RecordingMode.DISABLED, 1, 0,
                List.of("Image/PNG", " image/png ", "IMAGE/WEBP"), 0, 0, null, null);
        assertEquals(List.of("image/png", "image/webp"), viewOnly.allowedFrameContentTypes());
    }

    @Test
    void nonPositiveMaxViewers_defaultsToOne() {
        assertEquals(1, new ViewOnly(RecordingMode.DISABLED, 0, 0, null, 0, 0, null, null).maxViewersPerSession());
        assertEquals(1, new ViewOnly(RecordingMode.DISABLED, -3, 0, null, 0, 0, null, null).maxViewersPerSession());
    }
}
