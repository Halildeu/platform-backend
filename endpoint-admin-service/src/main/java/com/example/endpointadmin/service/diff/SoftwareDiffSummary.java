package com.example.endpointadmin.service.diff;

import com.example.endpointadmin.dto.v1.admin.AdminSoftwareInventoryDiffResponse.DiffStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * BE-024c (Faz 22.5 P2-A, Codex 019e88b5 iter-5 AGREE + 019e89a3 iter-3
 * AGREE) — pure summary core for the software-inventory diff path.
 * Mirrors the BE-024 canonical {@link DiffStatus} but ONLY carries the
 * count summary — never the full added/removed/versionChanged list
 * payloads the drawer endpoint streams.
 *
 * <h2>v2-c-pre-2-C-A source-pair ordering tuple</h2>
 *
 * <p>The {@code (sourceCapturedAt, sourceCreatedAt, sourceRowId)} triple
 * carries the canonical sort tuple of the latest history capture the
 * summary was computed against. The cache writer's UPSERT WHERE guard
 * uses this tuple to reject stale-overwrite refreshes (a refresh that
 * lands after a newer one has already written cache cannot regress to an
 * older source pair). For {@code NO_HISTORY} the triple is set to
 * sentinel values ({@code Instant.EPOCH} + zero UUID) so the guard treats
 * any incoming source-pair as progression.
 *
 * <h2>Two read paths</h2>
 * <ul>
 *   <li>Drawer (canonical) — full list compute via
 *       {@code EndpointSoftwareInventoryDiffService.diffLatest()}.</li>
 *   <li>Cache (this summary) — count-only compute via
 *       {@code EndpointSoftwareInventoryDiffService.summarize()} —
 *       written by the ingest hook + by the operator-triggered backfill;
 *       read back by the v2-d grid SCHEMA v5 (deferred to a separate PR).</li>
 * </ul>
 *
 * <p>Source ids reference history captures (NOT inventory snapshots) —
 * Codex 019e88b5 iter-2 must_fix #1: BE-024 algorithm reads the latest
 * two rows from {@code endpoint_software_inventory_state_history}, not
 * the inventory snapshot table.
 *
 * <p>Status / source-id pairing semantics:
 * <ul>
 *   <li>{@link DiffStatus#NO_HISTORY} — zero captures; both ids null;
 *       tuple = (EPOCH, EPOCH, zero UUID).</li>
 *   <li>{@link DiffStatus#INSUFFICIENT_HISTORY} — exactly one capture;
 *       only {@code toHistoryId} set; tuple from that capture.</li>
 *   <li>{@link DiffStatus#NO_CHANGE} — two captures, identical digest
 *       hash; both ids set; tuple from latest capture; all counts 0.</li>
 *   <li>{@link DiffStatus#OK} — two captures with deltas; both ids set;
 *       tuple from latest capture; counts populated.</li>
 * </ul>
 */
public record SoftwareDiffSummary(
        DiffStatus status,
        UUID fromHistoryId,
        UUID toHistoryId,
        int addedCount,
        int removedCount,
        int versionChangedCount,
        Instant sourceCapturedAt,
        Instant sourceCreatedAt,
        UUID sourceRowId
) {

    /** Zero UUID sentinel matching V28 migration's NO_HISTORY backfill. */
    public static final UUID ZERO_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    public static SoftwareDiffSummary noHistory() {
        return new SoftwareDiffSummary(DiffStatus.NO_HISTORY, null, null, 0, 0, 0,
                Instant.EPOCH, Instant.EPOCH, ZERO_UUID);
    }

    public static SoftwareDiffSummary insufficientHistory(UUID toHistoryId,
                                                           Instant sourceCapturedAt,
                                                           Instant sourceCreatedAt) {
        return new SoftwareDiffSummary(
                DiffStatus.INSUFFICIENT_HISTORY, null, toHistoryId, 0, 0, 0,
                sourceCapturedAt, sourceCreatedAt, toHistoryId);
    }

    /** Backwards-compatible overload (Codex 019e89a3 iter-3 absorb — pre-V28
     *  callers in legacy tests). Uses epoch sentinel so the writer guard
     *  treats the row as authoritative only over a NO_HISTORY existing
     *  row; in practice production summarize() always sets the full
     *  tuple via the timestamp-aware factory above. */
    public static SoftwareDiffSummary insufficientHistory(UUID toHistoryId) {
        return insufficientHistory(toHistoryId, Instant.EPOCH, Instant.EPOCH);
    }

    public static SoftwareDiffSummary noChange(UUID fromHistoryId, UUID toHistoryId,
                                                Instant sourceCapturedAt,
                                                Instant sourceCreatedAt) {
        return new SoftwareDiffSummary(
                DiffStatus.NO_CHANGE, fromHistoryId, toHistoryId, 0, 0, 0,
                sourceCapturedAt, sourceCreatedAt, toHistoryId);
    }

    public static SoftwareDiffSummary noChange(UUID fromHistoryId, UUID toHistoryId) {
        return noChange(fromHistoryId, toHistoryId, Instant.EPOCH, Instant.EPOCH);
    }

    public static SoftwareDiffSummary ok(UUID fromHistoryId, UUID toHistoryId,
                                          int addedCount, int removedCount, int versionChangedCount,
                                          Instant sourceCapturedAt,
                                          Instant sourceCreatedAt) {
        return new SoftwareDiffSummary(
                DiffStatus.OK, fromHistoryId, toHistoryId,
                addedCount, removedCount, versionChangedCount,
                sourceCapturedAt, sourceCreatedAt, toHistoryId);
    }

    public static SoftwareDiffSummary ok(UUID fromHistoryId, UUID toHistoryId,
                                          int addedCount, int removedCount, int versionChangedCount) {
        return ok(fromHistoryId, toHistoryId, addedCount, removedCount, versionChangedCount,
                Instant.EPOCH, Instant.EPOCH);
    }
}
