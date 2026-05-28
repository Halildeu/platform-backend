package com.example.endpointadmin.event;

import java.time.Instant;
import java.util.UUID;

/**
 * BE-022 — application event emitted when a hardware inventory
 * snapshot is persisted (Faz 22.5). Codex {@code 019e7007} iter-4
 * absorb — bounded audit metadata.
 *
 * <p>Audit-safe by construction: this event carries ONLY the bounded
 * metadata fields listed below. No MAC addresses, no IP literals, no
 * domain name, no BIOS / disk / system serials, no redacted payload
 * body, no probe summary text. Downstream listeners (BE-016 hash-chain
 * audit) can publish this event upstream without leaking hardware
 * identifiers.
 *
 * @param tenantId               the tenant the snapshot belongs to
 * @param deviceId               the device that produced the snapshot
 * @param snapshotId             the new snapshot's primary key
 * @param sourceCommandId        originating agent command (nullable
 *                               for manual/test ingest)
 * @param schemaVersion          payload schema version reported by
 *                               the agent
 * @param supported              whether the agent considered the OS
 *                               supported for full hardware probe
 * @param payloadHashSha256      SHA-256 of the sanitized hardware
 *                               payload — change-detection signal
 * @param diskCount              number of child disk rows persisted
 * @param networkInterfaceCount  number of child NIC rows persisted
 * @param collectedAt            agent-side collection timestamp
 */
public record HardwareInventorySnapshotPersistedEvent(
        UUID tenantId,
        UUID deviceId,
        UUID snapshotId,
        UUID sourceCommandId,
        Integer schemaVersion,
        Boolean supported,
        String payloadHashSha256,
        int diskCount,
        int networkInterfaceCount,
        Instant collectedAt) {
}
