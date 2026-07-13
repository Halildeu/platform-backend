package com.example.endpointadmin.remoteaccess.bridge.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.endpointadmin.service.EndpointAuditService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The fail-closed hash-chain audit boundary for the VIEW_ONLY viewer: VIEW_START / VIEW_STOP are recorded through
 * {@link EndpointAuditService#record} as metadata-only events (no screen bytes / frame hash), with a null device +
 * command (a remote-support observation is not a device command), and the operator subject as the audit actor.
 */
class RemoteBridgeViewerAuditServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SUBJECT = "operator@example.com";
    private static final String SESSION = "sess-1";
    private static final String DEVICE = "device-1";
    private static final String STREAM = "op-1";

    private final EndpointAuditService auditService = mock(EndpointAuditService.class);
    private final RemoteBridgeViewerAuditService viewerAudit = new RemoteBridgeViewerAuditService(auditService);

    @Test
    void recordViewStartWritesMetadataOnlyHashChainEvent() {
        viewerAudit.recordViewStart(TENANT, SUBJECT, SESSION, DEVICE, STREAM);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(
                eq(TENANT), isNull(), isNull(), // tenant; NO device, NO command
                eq("REMOTE_SUPPORT_SCREEN_OBSERVATION"), eq("VIEW_START"),
                eq(SUBJECT), eq(SESSION), meta.capture(), isNull(), isNull());
        assertThat(meta.getValue())
                .containsEntry("sessionId", SESSION)
                .containsEntry("deviceId", DEVICE)
                .containsEntry("streamId", STREAM)
                .containsEntry("recording", false)
                .containsEntry("attended", true)
                .containsEntry("capability", "VIEW_ONLY");
        // metadata-ONLY: never a screen payload / frame bytes / frame hash.
        assertThat(meta.getValue()).doesNotContainKeys("payload", "dataB64", "frameHash", "image", "ocr");
    }

    @Test
    void recordViewStopIncludesFrameCountAndStopAction() {
        viewerAudit.recordViewStop(TENANT, SUBJECT, SESSION, DEVICE, STREAM, 42L, 39L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(
                eq(TENANT), isNull(), isNull(),
                eq("REMOTE_SUPPORT_SCREEN_OBSERVATION"), eq("VIEW_STOP"),
                eq(SUBJECT), eq(SESSION), meta.capture(), isNull(), isNull());
        assertThat(meta.getValue())
                .containsEntry("framesDelivered", 42L)
                .containsEntry("framesRenderAcknowledged", 39L)
                .containsEntry("capability", "VIEW_ONLY")
                .doesNotContainKeys("payload", "dataB64", "frameHash");
    }

    @Test
    void recordViewStopRejectsImpossibleAcknowledgementCount() {
        assertThatThrownBy(() -> viewerAudit.recordViewStop(
                TENANT, SUBJECT, SESSION, DEVICE, STREAM, 2L, 3L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");
    }
}
