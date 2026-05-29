package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * BE-024 — latest-vs-previous software-inventory diff for a device
 * (Faz 22.5 software-inventory diff/history).
 *
 * <p>Always returned with HTTP 200 (even when there is no/insufficient
 * history) so a cross-tenant request and a "no data yet" request are
 * indistinguishable — device existence does not leak (Codex 019e75a5 (d)
 * absorb; mirrors the BE-022Q no-leak discipline). The {@code status} field
 * tells the client how to render:
 *
 * <ul>
 *   <li>{@link DiffStatus#OK} — two captures compared; {@code added} /
 *       {@code removed} / {@code versionChanged} populated (any may be
 *       empty).</li>
 *   <li>{@link DiffStatus#NO_CHANGE} — two captures compared but identical
 *       (e.g. a byte-identical re-collect); all three lists empty.</li>
 *   <li>{@link DiffStatus#INSUFFICIENT_HISTORY} — exactly one capture
 *       exists; nothing to compare against. All lists empty.</li>
 *   <li>{@link DiffStatus#NO_HISTORY} — zero captures (device never shipped
 *       a full apps[] payload, or unknown/cross-tenant device). All lists
 *       empty.</li>
 * </ul>
 */
public record AdminSoftwareInventoryDiffResponse(
        UUID deviceId,
        DiffStatus status,
        Instant fromCapturedAt,
        Instant toCapturedAt,
        Integer fromAppCount,
        Integer toAppCount,
        List<AdminSoftwareInventoryDiffEntryResponse> added,
        List<AdminSoftwareInventoryDiffEntryResponse> removed,
        List<AdminSoftwareInventoryDiffEntryResponse> versionChanged
) {

    /** Why the diff lists are (or are not) populated. */
    public enum DiffStatus {
        OK,
        NO_CHANGE,
        INSUFFICIENT_HISTORY,
        NO_HISTORY
    }

    /** No captures at all — empty diff, NO_HISTORY reason. */
    public static AdminSoftwareInventoryDiffResponse noHistory(UUID deviceId) {
        return new AdminSoftwareInventoryDiffResponse(
                deviceId, DiffStatus.NO_HISTORY,
                null, null, null, null,
                List.of(), List.of(), List.of());
    }

    /** Exactly one capture — nothing to compare; empty diff. */
    public static AdminSoftwareInventoryDiffResponse insufficientHistory(
            UUID deviceId, Instant toCapturedAt, Integer toAppCount) {
        return new AdminSoftwareInventoryDiffResponse(
                deviceId, DiffStatus.INSUFFICIENT_HISTORY,
                null, toCapturedAt, null, toAppCount,
                List.of(), List.of(), List.of());
    }
}
