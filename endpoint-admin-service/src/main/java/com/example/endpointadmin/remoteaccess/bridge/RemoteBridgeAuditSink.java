package com.example.endpointadmin.remoteaccess.bridge;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;

/**
 * Faz 22.6 T-1b — the broker's control-plane audit sink. The broker durably records each policy decision /
 * kill BEFORE acting on it; a durable-write failure MUST throw, because recording is mandatory and
 * fail-closed (ADR-0034 §6: {@code RECORDING_READY} is a precondition of any privileged action — a recorder
 * failure BLOCKS permit issuance, Codex 019eb9fb). The real implementation is the Phase-C {@code
 * SessionRecorder} / durable WORM sink, wired at the C/D transport; this seam keeps the broker testable.
 */
@FunctionalInterface
public interface RemoteBridgeAuditSink {

    /**
     * Durably record one control-plane audit event. MUST throw (any unchecked exception) if the durable write
     * does not commit — the broker treats a throw as fail-closed and issues no permit.
     */
    void record(RemoteBridgeMessages.AuditEvent event);
}
