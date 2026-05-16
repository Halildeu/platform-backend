package com.example.schema.model;

/**
 * Authoritative database-level options extracted from MSSQL {@code sys.databases}
 * + {@code sys.database_files} — {@code authoritative_mssql} truth tier
 * (ADR-0020 §2.3, capability M15 — Codex 019e32bc).
 *
 * <p>Phase B1-8 — the final B1 sprint item. Unlike the seven per-table
 * inventories, this is a <strong>single</strong> record describing the
 * connected database itself; on {@link SchemaSnapshot} it is a nullable
 * singleton (null when the extraction fails or the row is not visible), not a
 * list.
 *
 * <p>It feeds migration risk measurement: recovery model, compatibility level,
 * collation, snapshot-isolation / RCSI, page-verify and the ANSI / session
 * option defaults all change query semantics or operational behaviour on the
 * target engine. The {@code sys.database_files} aggregate ({@code dataFileCount}
 * etc.) sizes the cutover; physical file paths are deliberately NOT carried.
 *
 * <p>Last-backup metadata ({@code msdb.dbo.backupset}) is out of scope — a
 * separate database with separate permissions; an M15-adjacent future item.
 */
public record DatabaseOptionsInfo(
    String databaseName,
    String collation,
    int compatibilityLevel,
    String recoveryModel,
    boolean readCommittedSnapshotEnabled,
    String snapshotIsolationState,
    String pageVerifyOption,
    boolean autoCreateStatisticsEnabled,
    boolean autoUpdateStatisticsEnabled,
    boolean autoUpdateStatisticsAsyncEnabled,
    boolean autoShrinkEnabled,
    boolean autoCloseEnabled,
    boolean ansiNullsEnabled,
    boolean ansiPaddingEnabled,
    boolean ansiWarningsEnabled,
    boolean ansiNullDefaultEnabled,
    boolean arithAbortEnabled,
    boolean quotedIdentifierEnabled,
    boolean concatNullYieldsNull,
    boolean numericRoundAbortEnabled,
    int dataFileCount,
    int logFileCount,
    long dataFileSizeKb,
    long logFileSizeKb
) {}
