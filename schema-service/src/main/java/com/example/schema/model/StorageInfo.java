package com.example.schema.model;

/**
 * Authoritative per-table storage footprint extracted from MSSQL
 * {@code sys.dm_db_partition_stats} — {@code authoritative_mssql} truth tier
 * (ADR-0020 §2.3, capability M6 — Codex 019e329a).
 *
 * <p>Phase B1-6. Sizes are in kilobytes (page count × 8 KB), aggregated over
 * every partition and index of the table. The decomposition holds the
 * invariant {@code dataKb + indexKb + lobKb + rowOverflowKb == usedKb}, and
 * {@code reservedKb - usedKb} is the allocated-but-unused slack.
 *
 * <p>This feeds cutover volume planning: a {@code rowCount} alone understates
 * a table whose footprint is dominated by LOB or row-overflow data, which
 * transfers far more slowly than in-row rows. {@code lobKb} (real large-object
 * data) and {@code rowOverflowKb} (wide-row overflow) are kept distinct —
 * they drive different cutover actions.
 *
 * <p>{@code rowCount} is the base heap / clustered-index row count
 * ({@code index_id IN (0,1)}), NOT the sum across every nonclustered index.
 * Likewise {@code dataKb} counts only the base heap / clustered in-row pages;
 * nonclustered index in-row pages fall into {@code indexKb}.
 *
 * <p>Filegroup and compression are out of scope here — capability M7 (B3).
 */
public record StorageInfo(
    String table,
    String schema,
    long rowCount,
    long reservedKb,
    long usedKb,
    long dataKb,
    long indexKb,
    long lobKb,
    long rowOverflowKb
) {}
