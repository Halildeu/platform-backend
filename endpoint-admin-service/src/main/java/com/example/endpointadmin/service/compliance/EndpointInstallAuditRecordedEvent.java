package com.example.endpointadmin.service.compliance;

import java.time.Instant;
import java.util.UUID;

/**
 * BE-021 — published by {@code EndpointInstallAuditService} after a
 * terminal INSTALL_SOFTWARE result is recorded. Consumed by
 * {@link ComplianceInstallAuditEventListener} on AFTER_COMMIT to
 * trigger a background compliance re-evaluation. Pattern parity with
 * {@code SoftwareInventorySnapshotPersistedEvent} (Codex 019e6dfb
 * iter-3 P2-1 absorb).
 */
public record EndpointInstallAuditRecordedEvent(
        UUID tenantId,
        UUID deviceId,
        UUID installAuditId,
        Instant recordedAt) {
}
