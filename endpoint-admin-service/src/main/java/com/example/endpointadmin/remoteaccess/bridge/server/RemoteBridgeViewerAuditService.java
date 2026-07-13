package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.service.EndpointAuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Faz 22.6 #1580 — the fail-closed, hash-chained audit boundary for the VIEW_ONLY operator screen-observation
 * viewer (the pilot-enable HARD GATE: {@code remote-bridge.viewer.enabled} must not go live without it).
 *
 * <p>It wraps {@link EndpointAuditService#record} — which is {@code @Transactional(MANDATORY)} and acquires a
 * transaction-scoped per-tenant advisory chain lock — in a SHORT, dedicated {@code @Transactional} method.
 * That is deliberate: the viewer's SSE stream lives for the whole observation session, so the audit write must
 * NOT share the request/stream scope (which would hold the advisory lock for the entire stream). Each
 * start/stop is its own brief committed transaction.
 *
 * <p><b>Metadata-only.</b> No screen bytes, no frame hash, no OCR — only the session/device/stream identifiers
 * and the invariant pilot flags (recording-off, attended, VIEW_ONLY). The operator subject is the audit actor
 * (the full subject belongs in the tamper-evident chain; app logs only ever see a fingerprint).
 *
 * <p><b>Fail-closed contract (enforced by the caller).</b> The viewer controller records {@code VIEW_START}
 * BEFORE any frame is emitted and treats a throw as fail-closed (the operator does not observe). {@code VIEW_STOP}
 * is recorded on stream end.
 */
@Service
public class RemoteBridgeViewerAuditService {

    /** KVKK purpose / audit event type for operator screen observation (matches ADR-0034 §13 / D10 pilot). */
    static final String EVENT_TYPE = "REMOTE_SUPPORT_SCREEN_OBSERVATION";
    static final String ACTION_VIEW_START = "VIEW_START";
    static final String ACTION_VIEW_STOP = "VIEW_STOP";

    private final EndpointAuditService auditService;

    public RemoteBridgeViewerAuditService(EndpointAuditService auditService) {
        this.auditService = Objects.requireNonNull(auditService, "auditService");
    }

    /**
     * Record the start of an operator screen-observation, fail-closed: a thrown exception MUST prevent the
     * observation (the caller does not subscribe/stream). Runs in its own short transaction so the advisory
     * chain lock is held only for the write, never for the stream lifetime.
     */
    @Transactional
    public void recordViewStart(UUID tenantId, String operatorSubject, String sessionId, String deviceId,
                                String streamId) {
        auditService.record(tenantId, null, null, EVENT_TYPE, ACTION_VIEW_START, operatorSubject, sessionId,
                baseMetadata(sessionId, deviceId, streamId), null, null);
    }

    /**
     * Record the end of an operator screen-observation (best-effort at the caller: the stream is already ending,
     * so a STOP-audit failure is logged, not propagated). Includes sent and browser-rendered frame counts as
     * observation scope. These are metadata counters only; screen content is never recorded here.
     */
    @Transactional
    public void recordViewStop(UUID tenantId, String operatorSubject, String sessionId, String deviceId,
                               String streamId, long framesDelivered, long framesRenderAcknowledged) {
        if (framesDelivered < 0 || framesRenderAcknowledged < 0
                || framesRenderAcknowledged > framesDelivered) {
            throw new IllegalArgumentException("viewer STOP counters are inconsistent");
        }
        Map<String, Object> metadata = baseMetadata(sessionId, deviceId, streamId);
        metadata.put("framesDelivered", framesDelivered);
        metadata.put("framesRenderAcknowledged", framesRenderAcknowledged);
        auditService.record(tenantId, null, null, EVENT_TYPE, ACTION_VIEW_STOP, operatorSubject, sessionId,
                metadata, null, null);
    }

    private static Map<String, Object> baseMetadata(String sessionId, String deviceId, String streamId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", Objects.requireNonNullElse(sessionId, ""));
        metadata.put("deviceId", Objects.requireNonNullElse(deviceId, ""));
        metadata.put("streamId", Objects.requireNonNullElse(streamId, ""));
        metadata.put("recording", false);
        metadata.put("attended", true);
        metadata.put("capability", "VIEW_ONLY");
        return metadata;
    }
}
