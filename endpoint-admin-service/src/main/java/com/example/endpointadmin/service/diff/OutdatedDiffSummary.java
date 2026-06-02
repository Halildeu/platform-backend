package com.example.endpointadmin.service.diff;

import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareDiffResponse.DiffStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * BE-024c outdated-software diff summary (Faz 22.5 P2-A, Codex 019e88b5
 * iter-5 AGREE + 019e89a3 iter-3 AGREE). Parallel to
 * {@code SoftwareDiffSummary} but for the BE-024b outdated-software diff
 * path.
 *
 * <p>Source ids refer to {@code endpoint_outdated_software_snapshots}
 * (NOT a history table — outdated has no append-only history; the
 * snapshot table itself is the canonical AG-036 source-of-truth) —
 * field names {@code fromSnapshotId}/{@code toSnapshotId} reflect that.
 *
 * <p>Outdated carries a 4th count for {@code availableVersionBumped}
 * (BE-024b — canonical packageId same, installedVersion unchanged,
 * availableVersion changed).
 *
 * <h2>v2-c-pre-2-C-A source-pair ordering tuple</h2>
 *
 * <p>The {@code (sourceCapturedAt, sourceCreatedAt, sourceRowId)} triple
 * carries the canonical sort tuple of the latest outdated snapshot the
 * summary was computed against. The cache writer's UPSERT WHERE guard
 * uses this tuple to reject stale-overwrite refreshes. For
 * {@code NO_HISTORY} the triple is set to sentinel values
 * ({@code Instant.EPOCH} + zero UUID).
 */
public record OutdatedDiffSummary(
        DiffStatus status,
        UUID fromSnapshotId,
        UUID toSnapshotId,
        int addedCount,
        int removedCount,
        int versionChangedCount,
        int availableVersionBumpedCount,
        Instant sourceCapturedAt,
        Instant sourceCreatedAt,
        UUID sourceRowId
) {

    /** Zero UUID sentinel matching V28 migration's NO_HISTORY backfill. */
    public static final UUID ZERO_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    public static OutdatedDiffSummary noHistory() {
        return new OutdatedDiffSummary(
                DiffStatus.NO_HISTORY, null, null, 0, 0, 0, 0,
                Instant.EPOCH, Instant.EPOCH, ZERO_UUID);
    }

    public static OutdatedDiffSummary insufficientHistory(UUID toSnapshotId,
                                                           Instant sourceCapturedAt,
                                                           Instant sourceCreatedAt) {
        return new OutdatedDiffSummary(
                DiffStatus.INSUFFICIENT_HISTORY, null, toSnapshotId, 0, 0, 0, 0,
                sourceCapturedAt, sourceCreatedAt, toSnapshotId);
    }

    public static OutdatedDiffSummary insufficientHistory(UUID toSnapshotId) {
        return insufficientHistory(toSnapshotId, Instant.EPOCH, Instant.EPOCH);
    }

    public static OutdatedDiffSummary noChange(UUID fromSnapshotId, UUID toSnapshotId,
                                                Instant sourceCapturedAt,
                                                Instant sourceCreatedAt) {
        return new OutdatedDiffSummary(
                DiffStatus.NO_CHANGE, fromSnapshotId, toSnapshotId, 0, 0, 0, 0,
                sourceCapturedAt, sourceCreatedAt, toSnapshotId);
    }

    public static OutdatedDiffSummary noChange(UUID fromSnapshotId, UUID toSnapshotId) {
        return noChange(fromSnapshotId, toSnapshotId, Instant.EPOCH, Instant.EPOCH);
    }

    public static OutdatedDiffSummary ok(UUID fromSnapshotId, UUID toSnapshotId,
                                          int addedCount, int removedCount,
                                          int versionChangedCount,
                                          int availableVersionBumpedCount,
                                          Instant sourceCapturedAt,
                                          Instant sourceCreatedAt) {
        return new OutdatedDiffSummary(
                DiffStatus.OK, fromSnapshotId, toSnapshotId,
                addedCount, removedCount, versionChangedCount, availableVersionBumpedCount,
                sourceCapturedAt, sourceCreatedAt, toSnapshotId);
    }

    public static OutdatedDiffSummary ok(UUID fromSnapshotId, UUID toSnapshotId,
                                          int addedCount, int removedCount,
                                          int versionChangedCount,
                                          int availableVersionBumpedCount) {
        return ok(fromSnapshotId, toSnapshotId, addedCount, removedCount,
                versionChangedCount, availableVersionBumpedCount,
                Instant.EPOCH, Instant.EPOCH);
    }
}
